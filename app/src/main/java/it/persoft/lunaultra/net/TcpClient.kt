package it.persoft.lunaultra.net

import it.persoft.lunaultra.protocol.FrameAssembler
import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.Ucd2Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Trasporto TCP verso la camera: apre il socket sulla rete Wi-Fi, spezza il flusso in frame
 * e li ripubblica. Non conosce la semantica dei comandi (se ne occupa la sessione).
 */
class TcpClient(
    private val log: EventLog,
    private val binder: SocketBinder?,
) {

    private val writeMutex = Mutex()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var output: OutputStream? = null

    private var readJob: Job? = null

    private val _frames = MutableSharedFlow<Ucd2Frame>(replay = 0, extraBufferCapacity = 128)
    val frames: SharedFlow<Ucd2Frame> = _frames

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected

    /** Byte grezzi ricevuti, per la vista Diagnostica (utile a validare il layout dell'header). */
    private val _rawIn = MutableSharedFlow<ByteArray>(replay = 0, extraBufferCapacity = 64)
    val rawIn: SharedFlow<ByteArray> = _rawIn

    suspend fun connect(
        host: String,
        port: Int,
        scope: CoroutineScope,
        connectTimeoutMs: Int = 5_000,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        disconnect()
        runCatching {
            val s = Socket()
            binder?.let {
                if (it.bind(s)) log.debug("Socket associato alla rete Wi-Fi")
                else log.warn("Socket non associato al Wi-Fi: verrà usato il routing di default")
            }
            s.tcpNoDelay = true
            s.soTimeout = 0
            s.connect(InetSocketAddress(host, port), connectTimeoutMs)
            socket = s
            output = s.getOutputStream()
            _connected.value = true
            log.info("Connesso a $host:$port")
            readJob = scope.launch(Dispatchers.IO) { readLoop(s.getInputStream()) }
        }.onFailure {
            log.error("Connessione a $host:$port fallita: ${it.message}")
            cleanup()
        }
    }

    private suspend fun readLoop(input: InputStream) {
        val assembler = FrameAssembler()
        val chunk = ByteArray(8 * 1024)
        try {
            while (currentCoroutineContext().isActive) {
                val read = input.read(chunk)
                if (read < 0) {
                    log.warn("La camera ha chiuso la connessione")
                    break
                }
                if (read == 0) continue
                val copy = chunk.copyOf(read)
                _rawIn.tryEmit(copy)
                assembler.append(copy, read)
                val frames = assembler.drain { reason -> log.warn("Frame scartato: $reason") }
                for (frame in frames) {
                    if (frame.isCommandFrame) {
                        log.rx(
                            "%s req=%d len=%dB".format(
                                LunaProtocolCodes.describe(frame.code), frame.requestId, frame.payload.size,
                            )
                        )
                    }
                    _frames.tryEmit(frame)
                }
            }
        } catch (e: Exception) {
            if (_connected.value) log.error("Errore in lettura: ${e.message}")
        } finally {
            cleanup()
        }
    }

    suspend fun send(bytes: ByteArray): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val out = output ?: error("Non connesso")
            writeMutex.withLock {
                out.write(bytes)
                out.flush()
            }
            log.tx(Hex.encode(bytes, limit = 48))
        }.onFailure { log.error("Errore in scrittura: ${it.message}") }
    }

    suspend fun disconnect() {
        readJob?.let { runCatching { it.cancelAndJoin() } }
        readJob = null
        cleanup()
    }

    private fun cleanup() {
        runCatching { socket?.close() }
        socket = null
        output = null
        if (_connected.value) {
            _connected.value = false
            log.info("Disconnesso")
        }
    }
}
