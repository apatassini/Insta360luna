package it.persoft.lunaultra.camera

import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.TcpClient
import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoReader
import it.persoft.lunaultra.protocol.Ucd2
import it.persoft.lunaultra.protocol.Ucd2Frame
import kotlinx.coroutines.CompletableDeferred
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

/**
 * Sessione di controllo UCD2: handshake, keep-alive e correlazione richiesta/risposta.
 *
 * Due dettagli del protocollo che non sono intuitivi:
 * - la sessione si autorizza inviando il frame di *stream hello*, non un comando;
 * - a correlare risposta e richiesta è il `requestId` dell'intestazione di comando, non il
 *   `seq` dell'header UCD2, che scorre per ogni frame inviato.
 */
class CameraSession(
    private val log: EventLog,
    private val client: TcpClient,
    private val scope: CoroutineScope,
    private val settings: StateFlow<AppSettings>,
) {

    /** Valori iniziali osservati nel traffico dell'app ufficiale. */
    private val sequence = AtomicInteger(0x24)
    private val requestId = AtomicInteger(1)

    private val pending = ConcurrentHashMap<Int, CompletableDeferred<Ucd2Frame>>()

    private val _state = MutableStateFlow(ConnectionState.DISCONNECTED)
    val state: StateFlow<ConnectionState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val _notifications = MutableSharedFlow<Ucd2Frame>(replay = 0, extraBufferCapacity = 64)
    val notifications: SharedFlow<Ucd2Frame> = _notifications

    /**
     * Payload video dell'anteprima dal vivo: stream elementare Annex-B, già privato
     * dell'intestazione media. Buffer ampio e `tryEmit`: se il decoder è in ritardo si perdono
     * fotogrammi, che è preferibile a far crescere la memoria senza limite.
     */
    private val _videoFrames = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 256)
    val videoFrames: SharedFlow<ByteArray> = _videoFrames

    private var keepAliveJob: Job? = null
    private var dispatchJob: Job? = null

    suspend fun connect(): Result<Unit> {
        val cfg = settings.value
        _lastError.value = null
        _state.value = ConnectionState.CONNECTING
        startDispatcher()

        val result = client.connect(cfg.host, cfg.port, scope)
        if (result.isFailure) {
            _state.value = ConnectionState.ERROR
            _lastError.value = result.exceptionOrNull()?.message ?: "Connessione fallita"
            return Result.failure(result.exceptionOrNull() ?: IllegalStateException("Connessione fallita"))
        }

        _state.value = ConnectionState.HANDSHAKE
        val hello = client.send(Ucd2.hello(nextSequence()))
        if (hello.isFailure) {
            _state.value = ConnectionState.ERROR
            _lastError.value = "Handshake non inviato: ${hello.exceptionOrNull()?.message}"
            return Result.failure(hello.exceptionOrNull() ?: IllegalStateException("Handshake fallito"))
        }
        log.info("Handshake inviato (stream hello)")

        // La camera impiega un attimo ad accettare i comandi dopo l'hello.
        delay(HANDSHAKE_SETTLE_MS)
        _state.value = ConnectionState.CONNECTED
        startKeepAlive()
        return Result.success(Unit)
    }

    suspend fun disconnect() {
        keepAliveJob?.cancel()
        keepAliveJob = null
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
                if (frame.type == Ucd2.TYPE_MEDIA) {
                    // Solo il video principale: 0x30 è l'anteprima secondaria, 0x40 il giroscopio.
                    if (frame.substream == Ucd2.MEDIA_VIDEO && frame.payload.isNotEmpty()) {
                        _videoFrames.tryEmit(frame.payload)
                    }
                    return@collect
                }
                if (!frame.isCommandFrame) return@collect
                val waiter = pending.remove(frame.requestId)
                if (waiter != null) waiter.complete(frame) else _notifications.tryEmit(frame)
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

    /** Il keep-alive è lo stesso frame dell'handshake, ripetuto. */
    private fun startKeepAlive() {
        keepAliveJob?.cancel()
        val periodSeconds = settings.value.keepAliveSeconds
        if (periodSeconds <= 0) return
        keepAliveJob = scope.launch {
            while (isActive && _state.value == ConnectionState.CONNECTED) {
                delay(periodSeconds * 1000L)
                client.send(Ucd2.hello(nextSequence()))
                    .onFailure { log.debug("Keep-alive non inviato: ${it.message}") }
            }
        }
    }

    /**
     * Invia un comando e attende la risposta.
     *
     * Una risposta che contiene un messaggio `Error` viene restituita come fallimento con
     * [CameraErrorException], così il chiamante distingue "comando inesistente" da
     * "argomenti sbagliati" senza reinterpretare i byte.
     */
    suspend fun request(
        code: Int,
        body: ByteArray = ByteArray(0),
        timeoutMs: Long = settings.value.requestTimeoutMs,
    ): Result<Ucd2Frame> {
        val raw = requestRaw(code, body, timeoutMs)
        val frame = raw.getOrElse { return Result.failure(it) }
        val error = LunaError.parse(frame.payload)
        return if (error != null) {
            Result.failure(CameraErrorException(LunaProtocolCodes.describe(code), error))
        } else {
            Result.success(frame)
        }
    }

    /**
     * Come [request] ma senza interpretare il corpo: restituisce la risposta anche quando è
     * un errore. È ciò che serve allo scanner, per cui l'errore *è* il dato.
     */
    suspend fun requestRaw(
        code: Int,
        body: ByteArray = ByteArray(0),
        timeoutMs: Long = settings.value.requestTimeoutMs,
    ): Result<Ucd2Frame> {
        if (!client.connected.value) return Result.failure(IllegalStateException("Non connesso"))
        val id = nextRequestId()
        val waiter = CompletableDeferred<Ucd2Frame>()
        pending[id] = waiter
        val sent = client.send(Ucd2.command(nextSequence(), code, id, body))
        if (sent.isFailure) {
            pending.remove(id)
            return Result.failure(sent.exceptionOrNull() ?: IllegalStateException("Invio fallito"))
        }
        log.tx(
            "%s (%d) req=%d len=%dB".format(LunaProtocolCodes.describe(code), code, id, body.size),
            detail = if (body.isEmpty()) "(nessun payload)" else
                "hex: ${Hex.encode(body, limit = 96)}\n${ProtoReader(body).describe()}",
        )
        val response = withTimeoutOrNull(timeoutMs) { waiter.await() }
        pending.remove(id)
        return if (response == null) {
            Result.failure(TimeoutException(LunaProtocolCodes.describe(code), timeoutMs))
        } else {
            Result.success(response)
        }
    }

    /** Invio senza attesa della risposta, per i comandi ripetuti ad alta frequenza. */
    suspend fun fire(code: Int, body: ByteArray = ByteArray(0)): Result<Unit> {
        if (!client.connected.value) return Result.failure(IllegalStateException("Non connesso"))
        return client.send(Ucd2.command(nextSequence(), code, nextRequestId(), body))
    }

    private fun nextSequence(): Int = sequence.getAndIncrement() and 0xFF

    private fun nextRequestId(): Int {
        val next = requestId.getAndIncrement() and 0xFFFF
        return if (next == 0) requestId.getAndIncrement() and 0xFFFF else next
    }

    class TimeoutException(label: String, timeoutMs: Long) :
        IllegalStateException("Nessuna risposta per $label entro $timeoutMs ms")

    class CameraErrorException(label: String, val error: LunaError) :
        IllegalStateException(
            "La camera ha rifiutato $label: ${error.name}" +
                (error.message?.let { " (\"$it\")" } ?: "")
        )

    companion object {
        const val HANDSHAKE_SETTLE_MS = 1_500L
    }
}
