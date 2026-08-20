package it.persoft.lunaultra.preview

import android.graphics.Bitmap
import android.view.Surface
import it.persoft.lunaultra.camera.CameraSession
import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.SocketBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Come sta arrivando l'anteprima. */
enum class PreviewSource { NESSUNA, MJPEG, VIDEO }

data class PreviewState(
    val active: Boolean = false,
    val source: PreviewSource = PreviewSource.NESSUNA,
    val frame: Bitmap? = null,
    val framesDecoded: Long = 0,
    val bytesReceived: Long = 0,
    val message: String? = null,
) {
    /** Il flusso video disegna su una Surface, il MJPEG su una Image: la UI deve saperlo. */
    val usesSurface: Boolean get() = source == PreviewSource.VIDEO
}

/**
 * Anteprima dal vivo, con due trasporti.
 *
 * Si prova prima il MJPEG dell'endpoint OSC: quando la camera lo offre è molto più semplice —
 * niente decoder, niente keyframe da aspettare, un JPEG per fotogramma. Se non risponde si
 * ripiega sullo stream della sessione di controllo, che è H.264 grezzo e passa dal decoder.
 *
 * La sorgente scelta viene mostrata in chiaro nella UI: sapere quale delle due sta funzionando
 * è la prima informazione utile quando l'anteprima resta nera.
 */
class PreviewController(
    private val session: CameraSession,
    private val commands: LunaCommands,
    private val settings: StateFlow<AppSettings>,
    private val binder: SocketBinder?,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {

    private val _state = MutableStateFlow(PreviewState())
    val state: StateFlow<PreviewState> = _state

    private val decoder = VideoDecoder(log)

    private var job: Job? = null

    @Volatile
    private var surface: Surface? = null

    /** La UI comunica la Surface appena è pronta; il flusso video parte solo quando c'è. */
    fun attachSurface(target: Surface?) {
        surface = target
    }

    fun start() {
        if (_state.value.active) return
        job?.cancel()
        _state.value = PreviewState(active = true, message = "Avvio anteprima…")
        // Fuori dal thread principale: qui si fa I/O di rete e si alimenta un decoder video,
        // entrambe cose che sul main thread bloccano la UI (e la rete la fa proprio crashare).
        job = scope.launch(Dispatchers.IO) {
            val host = settings.value.host
            if (MjpegStream.isAvailable(host, binder)) {
                log.info("Anteprima: la camera offre il MJPEG OSC, uso quello")
                runMjpeg(host)
            } else {
                log.info("Anteprima: nessun MJPEG OSC, passo allo stream della sessione")
                runControlStream()
            }
        }
    }

    private suspend fun runMjpeg(host: String) {
        _state.value = _state.value.copy(source = PreviewSource.MJPEG, message = null)
        var count = 0L
        try {
            MjpegStream.frames(host, binder).collect { bitmap ->
                count++
                _state.value = _state.value.copy(
                    frame = bitmap,
                    framesDecoded = count,
                    message = null,
                )
            }
            _state.value = _state.value.copy(message = "Il flusso MJPEG si è chiuso")
        } catch (e: Exception) {
            log.warn("Anteprima MJPEG interrotta: ${e.message}")
            _state.value = _state.value.copy(message = "MJPEG interrotto: ${e.message}")
        }
    }

    private suspend fun runControlStream() {
        _state.value = _state.value.copy(
            source = PreviewSource.VIDEO,
            message = "In attesa del primo keyframe…",
        )
        commands.startLiveStream()
            .onFailure {
                log.error("START_LIVE_STREAM rifiutato: ${it.message}")
                _state.value = _state.value.copy(
                    active = false,
                    source = PreviewSource.NESSUNA,
                    message = "La camera ha rifiutato l'anteprima: ${it.message}",
                )
                return
            }

        session.videoFrames.collect { chunk ->
            val target = surface
            if (target == null || !target.isValid) return@collect
            decoder.feed(target, chunk)
            _state.value = _state.value.copy(
                framesDecoded = decoder.framesDecoded,
                bytesReceived = decoder.bytesReceived,
                message = if (decoder.framesDecoded > 0) null else "In attesa del primo keyframe…",
            )
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        decoder.release()
        if (_state.value.source == PreviewSource.VIDEO) {
            scope.launch { commands.stopLiveStream() }
        }
        _state.value = PreviewState()
    }
}
