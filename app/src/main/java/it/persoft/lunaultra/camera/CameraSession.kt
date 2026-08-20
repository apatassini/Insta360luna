package it.persoft.lunaultra.camera

import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.TcpClient
import it.persoft.lunaultra.protocol.Ucd2Codec
import it.persoft.lunaultra.protocol.Ucd2Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred

/**
 * Gestisce la sessione applicativa: numerazione dei messaggi, correlazione richiesta/risposta,
 * handshake e keep-alive. Il trasporto è delegato a [TcpClient].
 */
class CameraSession(
    private val log: EventLog,
    private val client: TcpClient,
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettings>,
    private val registry: CommandRegistry,
) {

    private val sequence = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Ucd2Frame>>()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _notifications = MutableSharedFlow<Ucd2Frame>(replay = 0, extraBufferCapacity = 64)
    val notifications: SharedFlow<Ucd2Frame> = _notifications

    private var codec: Ucd2Codec = Ucd2Codec(settings.value.layout.toLayout())
    private var keepAliveJob: Job? = null
    private var dispatchJob: Job? = null

    val currentCodec: Ucd2Codec get() = codec

    suspend fun connect(): Result<Unit> {
        val cfg = settings.value
        _lastError.value = null
        _state.value = ConnectionState.CONNECTING
        codec = Ucd2Codec(cfg.layout.toLayout())
        startDispatcher()

        val result = client.connect(cfg.host, cfg.port, codec, scope)
        if (result.isFailure) {
            _state.value = ConnectionState.ERROR
            _lastError.value = result.exceptionOrNull()?.message ?: "Connessione fallita"
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException("Connessione fallita"))
        }

        if (cfg.handshakeEnabled) {
            val handshakeId = registry.idOf(LunaCommand.CONNECT)
            if (handshakeId == null) {
                log.warn("Handshake saltato: id di ${LunaCommand.CONNECT.key} non configurato")
            } else {
                _state.value = ConnectionState.HANDSHAKE
                val response = request(LunaCommand.CONNECT, ByteArray(0))
                response.onFailure { log.warn("Handshake senza risposta: ${it.message}") }
                response.onSuccess { log.info("Handshake completato (err=${it.errorCode})") }
            }
        }

        _state.value = ConnectionState.CONNECTED
        startKeepAlive()
        return Result.success(Unit)
    }

    suspend fun disconnect() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        if (_state.value == ConnectionState.CONNECTED && registry.isConfigured(LunaCommand.DISCONNECT)) {
            runCatching { request(LunaCommand.DISCONNECT, ByteArray(0), timeoutMs = 500) }
        }
        client.disconnect()
        dispatchJob?.cancel()
        dispatchJob = null
        pending.values.forEach { it.cancel() }
        pending.clear()
        _state.value = ConnectionState.DISCONNECTED
    }

    private fun startDispatcher() {
        if (dispatchJob?.isActive == true) return
        dispatchJob = scope.launch {
            client.frames.collect { frame ->
                val waiter = pending.remove(frame.sequence)
                if (waiter != null && frame.type != Ucd2Frame.TYPE_NOTIFICATION) {
                    waiter.complete(frame)
                } else {
                    _notifications.tryEmit(frame)
                }
            }
        }
        scope.launch {
            client.connected.collect { connected ->
                if (!connected && _state.value != ConnectionState.DISCONNECTED) {
                    _state.value = ConnectionState.DISCONNECTED
                    keepAliveJob?.cancel()
                }
            }
        }
    }

    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        val periodSeconds = settings.value.keepAliveSeconds
        if (periodSeconds <= 0 || !registry.isConfigured(LunaCommand.KEEP_ALIVE)) return
        keepAliveJob = scope.launch {
            while (isActive && _state.value == ConnectionState.CONNECTED) {
                delay(periodSeconds * 1000L)
                request(LunaCommand.KEEP_ALIVE, ByteArray(0), timeoutMs = 1_500)
                    .onFailure { log.debug("Keep-alive senza risposta") }
            }
        }
    }

    /** Invia un comando simbolico e attende la risposta correlata. */
    suspend fun request(
        command: LunaCommand,
        payload: ByteArray,
        timeoutMs: Long = settings.value.requestTimeoutMs,
    ): Result<Ucd2Frame> {
        val id = registry.idOf(command)
            ?: return Result.failure(
                UnconfiguredCommandException(command)
            )
        return requestRaw(id, payload, timeoutMs, label = command.key)
    }

    /** Invia un comando "a fuoco e dimentica": non attende risposta (utile ad alta frequenza). */
    suspend fun fire(command: LunaCommand, payload: ByteArray): Result<Unit> {
        val id = registry.idOf(command) ?: return Result.failure(UnconfiguredCommandException(command))
        val frame = Ucd2Frame(
            commandId = id,
            sequence = nextSequence(),
            type = Ucd2Frame.TYPE_REQUEST,
            payload = payload,
        )
        return client.send(codec.encode(frame))
    }

    suspend fun requestRaw(
        commandId: Int,
        payload: ByteArray,
        timeoutMs: Long = settings.value.requestTimeoutMs,
        label: String = "0x%08X".format(commandId),
    ): Result<Ucd2Frame> {
        if (!client.connected.value) return Result.failure(IllegalStateException("Non connesso"))
        val seq = nextSequence()
        val waiter = CompletableDeferred<Ucd2Frame>()
        pending[seq] = waiter
        val frame = Ucd2Frame(commandId = commandId, sequence = seq, type = Ucd2Frame.TYPE_REQUEST, payload = payload)
        val sent = client.send(codec.encode(frame))
        if (sent.isFailure) {
            pending.remove(seq)
            return Result.failure(sent.exceptionOrNull() ?: IllegalStateException("Invio fallito"))
        }
        log.debug("→ $label seq=$seq payload=${payload.size}B")
        val response = withTimeoutOrNull(timeoutMs) { waiter.await() }
        pending.remove(seq)
        return when {
            response == null -> Result.failure(TimeoutException(label, timeoutMs))
            response.isError -> Result.failure(CameraErrorException(label, response.errorCode))
            else -> Result.success(response)
        }
    }

    private fun nextSequence(): Int {
        val bits = settings.value.layout.sequenceSize.coerceIn(1, 4) * 8
        val mask = if (bits >= 31) Int.MAX_VALUE else (1 shl bits) - 1
        val next = sequence.incrementAndGet() and mask
        return if (next == 0) sequence.incrementAndGet() and mask else next
    }

    class UnconfiguredCommandException(val command: LunaCommand) :
        IllegalStateException("Id non configurato per ${command.key}: impostalo in Diagnostica")

    class TimeoutException(label: String, timeoutMs: Long) :
        IllegalStateException("Nessuna risposta per $label entro $timeoutMs ms")

    class CameraErrorException(label: String, val code: Int) :
        IllegalStateException("La camera ha risposto con errore $code a $label")
}
