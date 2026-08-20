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
            // Dimensioni indicative: il decoder le corregge dallo SPS del flusso.
            val format = MediaFormat.createVideoFormat(kind.mime, 1440, 720)
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
            if (index < 0) break
            // render = true: il fotogramma va direttamente sulla Surface.
            decoder.releaseOutputBuffer(index, true)
            framesDecoded++
        }
    }

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

        /** Passo dei timestamp: la camera non ne manda, e a 30 fps questo è il valore giusto. */
        const val FRAME_INTERVAL_US = 33_333L
    }
}
