package it.persoft.lunaultra.preview

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import it.persoft.lunaultra.net.EventLog
import java.util.concurrent.atomic.AtomicLong

/**
 * Decodifica lo stream elementare dell'anteprima su una [Surface].
 *
 * Due dettagli non ovvi, entrambi imparati dal modo in cui la camera manda i dati:
 *
 * - il decoder non parte da un punto qualsiasi del flusso. Finché non arriva un keyframe i
 *   dati vengono scartati: alimentarlo con fotogrammi che dipendono da un riferimento mai
 *   ricevuto produce solo artefatti o un errore;
 * - il codec non è dichiarato da nessuna parte, va riconosciuto dai set di parametri nel
 *   flusso. Sulla Luna Ultra (firmware 1.0.288) l'anteprima arriva in **H.265**, non H.264:
 *   il riconoscimento resta dinamico perché altre risoluzioni potrebbero cambiarlo.
 */
class VideoDecoder(private val log: EventLog) {

    private var codec: MediaCodec? = null
    private var codecKind: AnnexB.Codec? = null

    /**
     * La Surface con cui il decoder è stato configurato.
     *
     * Il decoder tiene la Surface che gli è stata data al `configure`, e la SurfaceView ne
     * crea una nuova ogni volta che viene ricomposta o esce e rientra nello schermo. Continuare
     * a disegnare sulla precedente fa fallire il decoder con "rendering to non-initialized
     * (obsolete) surface": va riconfigurato sulla nuova.
     */
    private var codecSurface: Surface? = null

    private var sawKeyframe = false
    private val presentationUs = AtomicLong(0)

    @Volatile
    var framesDecoded: Long = 0
        private set

    @Volatile
    var bytesReceived: Long = 0
        private set

    @Volatile
    var displayWidth: Int = 0
        private set

    @Volatile
    var displayHeight: Int = 0
        private set

    @Volatile
    var displayAspectRatio: Float = PreviewState.DEFAULT_PREVIEW_ASPECT_RATIO
        private set

    val isRunning: Boolean get() = codec != null

    /**
     * Alimenta il decoder con un pezzo di stream. Il primo blocco utile fa partire il decoder:
     * prima di allora serve a riconoscere il codec.
     */
    @Synchronized
    fun feed(surface: Surface, chunk: ByteArray) {
        bytesReceived += chunk.size

        if (codec != null && codecSurface !== surface) {
            log.info("Anteprima: la Surface è cambiata, riconfiguro il decoder")
            release()
        }

        if (codec == null) {
            val kind = codecKind ?: AnnexB.detectCodec(chunk) ?: return
            codecKind = kind
            if (!AnnexB.containsKeyframe(chunk, kind)) return
            start(surface, kind)
        }

        val decoder = codec ?: return
        val kind = codecKind ?: return

        if (!sawKeyframe) {
            if (!AnnexB.containsKeyframe(chunk, kind)) return
            sawKeyframe = true
        }

        try {
            queue(decoder, chunk)
            drain(decoder)
        } catch (e: IllegalStateException) {
            log.error("Decoder video in errore, riavvio: ${e.message}")
            release()
        }
    }

    private fun start(surface: Surface, kind: AnnexB.Codec) {
        try {
            // Dimensioni soltanto indicative: il decoder le sostituisce con quelle dello SPS.
            // Il vecchio 1440×720 era 2:1 e poteva deformare i primi fotogrammi.
            val format = MediaFormat.createVideoFormat(kind.mime, 1280, 720)
            val created = MediaCodec.createDecoderByType(kind.mime)
            created.configure(format, surface, null, 0)
            created.start()
            codec = created
            codecSurface = surface
            sawKeyframe = false
            framesDecoded = 0
            log.info("Anteprima: decoder ${kind.mime} avviato")
        } catch (e: Exception) {
            log.error("Impossibile avviare il decoder ${kind.mime}: ${e.message}")
            codec = null
            codecSurface = null
        }
    }

    private fun queue(decoder: MediaCodec, chunk: ByteArray) {
        val index = decoder.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (index < 0) return // Nessun buffer libero: il pezzo si perde, l'anteprima prosegue.
        val buffer = decoder.getInputBuffer(index) ?: return
        buffer.clear()
        if (buffer.remaining() < chunk.size) {
            decoder.queueInputBuffer(index, 0, 0, 0, 0)
            return
        }
        buffer.put(chunk)
        decoder.queueInputBuffer(index, 0, chunk.size, presentationUs.addAndGet(FRAME_INTERVAL_US), 0)
    }

    private fun drain(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = decoder.dequeueOutputBuffer(info, 0)
            when {
                index >= 0 -> {
                    // render = true: il fotogramma va direttamente sulla Surface.
                    decoder.releaseOutputBuffer(index, true)
                    framesDecoded++
                }

                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> updateDisplayFormat(decoder.outputFormat)
                index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> return
            }
        }
    }

    /** Usa l'area visibile (crop SPS), non stride e buffer grezzi, per dimensionare la UI. */
    private fun updateDisplayFormat(format: MediaFormat) {
        val codedWidth = format.intOrNull(MediaFormat.KEY_WIDTH) ?: return
        val codedHeight = format.intOrNull(MediaFormat.KEY_HEIGHT) ?: return
        val cropLeft = format.intOrNull(MediaFormat.KEY_CROP_LEFT) ?: 0
        val cropRight = format.intOrNull(MediaFormat.KEY_CROP_RIGHT) ?: (codedWidth - 1)
        val cropTop = format.intOrNull(MediaFormat.KEY_CROP_TOP) ?: 0
        val cropBottom = format.intOrNull(MediaFormat.KEY_CROP_BOTTOM) ?: (codedHeight - 1)
        var width = (cropRight - cropLeft + 1).coerceAtLeast(1)
        var height = (cropBottom - cropTop + 1).coerceAtLeast(1)
        val rotation = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
        if (rotation == 90 || rotation == 270) {
            val swapped = width
            width = height
            height = swapped
        }
        val sarWidth = format.intOrNull(SAR_WIDTH_KEY)?.coerceAtLeast(1) ?: 1
        val sarHeight = format.intOrNull(SAR_HEIGHT_KEY)?.coerceAtLeast(1) ?: 1
        displayWidth = width
        displayHeight = height
        displayAspectRatio = width.toFloat() * sarWidth / (height.toFloat() * sarHeight)
        log.info(
            "Anteprima: formato visibile ${width}×${height}, rapporto " +
                "%.3f".format(displayAspectRatio),
        )
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (!containsKey(key)) null else runCatching { getInteger(key) }.getOrNull()

    @Synchronized
    fun release() {
        codec?.let {
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        codec = null
        codecSurface = null
        // Il codec riconosciuto resta valido: è una proprietà del flusso, non della Surface.
        sawKeyframe = false
        presentationUs.set(0)
    }

    private companion object {
        const val INPUT_TIMEOUT_US = 10_000L
        const val SAR_WIDTH_KEY = "sar-width"
        const val SAR_HEIGHT_KEY = "sar-height"

        /** Passo dei timestamp: la camera non ne manda, e a 30 fps questo è il valore giusto. */
        const val FRAME_INTERVAL_US = 33_333L
    }
}
