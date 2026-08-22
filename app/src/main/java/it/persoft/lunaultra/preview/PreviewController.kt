package it.persoft.lunaultra.preview

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.Surface
import it.persoft.lunaultra.camera.CameraSession
import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.SocketBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt

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

    /**
     * Congela l'inquadratura corrente in un JPEG quadrato e molto piccolo per la diagnostica.
     *
     * Con MJPEG il bitmap è già disponibile. Con lo stream H.265 il decoder disegna invece
     * direttamente su una Surface: [PixelCopy] è l'unico modo affidabile per leggere proprio
     * ciò che l'utente vede, senza avviare un secondo decoder. L'immagine completa viene
     * adattata dentro 256×256 con bande nere, non tagliata: per confrontare due waypoint
     * servono anche i bordi dell'inquadratura.
     */
    suspend fun captureThumbnailJpeg(size: Int = DIAGNOSTIC_THUMB_SIZE): ByteArray? {
        val safeSize = size.coerceIn(64, 512)
        if (!_state.value.active) start()
        val deadline = System.nanoTime() + DIAGNOSTIC_FRAME_WAIT_MS * 1_000_000L
        while (!hasCapturableFrame() && System.nanoTime() < deadline) delay(80L)
        val source = when (_state.value.source) {
            PreviewSource.MJPEG -> _state.value.frame
            PreviewSource.VIDEO -> copySurfaceFrame()
            PreviewSource.NESSUNA -> null
        } ?: return null

        val square = squareFit(source, safeSize)
        return ByteArrayOutputStream().use { output ->
            if (!square.compress(Bitmap.CompressFormat.JPEG, DIAGNOSTIC_JPEG_QUALITY, output)) null
            else output.toByteArray()
        }
    }

    private fun hasCapturableFrame(): Boolean = when (_state.value.source) {
        PreviewSource.MJPEG -> _state.value.frame != null
        PreviewSource.VIDEO -> _state.value.framesDecoded > 0 && surface?.isValid == true
        PreviewSource.NESSUNA -> false
    }

    /** Registra in un solo evento coordinate e inquadratura reale, quando disponibile. */
    suspend fun logSnapshot(message: String, detail: String): ByteArray? {
        val jpeg = captureThumbnailJpeg()
        val fullDetail = if (jpeg == null) "$detail\nMiniatura: non disponibile (anteprima spenta o senza frame)" else detail
        log.info(message, fullDetail, jpeg)
        return jpeg
    }

    private suspend fun copySurfaceFrame(): Bitmap? {
        val target = surface?.takeIf(Surface::isValid) ?: return null
        // La preview Luna è 16:9. Si copia già ridotta: il log non deve trattenere frame HD.
        val bitmap = Bitmap.createBitmap(
            DIAGNOSTIC_THUMB_SIZE,
            (DIAGNOSTIC_THUMB_SIZE * 9f / 16f).roundToInt(),
            Bitmap.Config.ARGB_8888,
        )
        val success = suspendCancellableCoroutine { continuation ->
            PixelCopy.request(target, bitmap, { result ->
                if (continuation.isActive) continuation.resume(result == PixelCopy.SUCCESS)
            }, Handler(Looper.getMainLooper()))
        }
        return bitmap.takeIf { success }
    }

    private fun squareFit(source: Bitmap, size: Int): Bitmap {
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK)
        val scale = minOf(size.toFloat() / source.width, size.toFloat() / source.height)
        val width = source.width * scale
        val height = source.height * scale
        val left = (size - width) / 2f
        val top = (size - height) / 2f
        canvas.drawBitmap(
            source,
            null,
            RectF(left, top, left + width, top + height),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return result
    }

    private companion object {
        const val DIAGNOSTIC_THUMB_SIZE = 256
        const val DIAGNOSTIC_JPEG_QUALITY = 78
        const val DIAGNOSTIC_FRAME_WAIT_MS = 2_500L
    }
}
