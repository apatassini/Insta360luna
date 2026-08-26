package it.persoft.lunaultra.stitch

import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.Locale

/**
 * Il passaporto di uno scatto di panoramica, scritto nell'EXIF della foto.
 *
 * La camera non lo sa, che una foto appartiene a una panoramica: lo sa l'app, e lo sa nel
 * momento dello scatto — a che panorama appartiene, quante sorelle ha, a che angolo guardava.
 * Scriverlo dentro il file vuol dire non doverlo più indovinare: una copia taggata si
 * riconosce anche fra un mese, si rimette in ordine da sola e si riunisce con gli angoli
 * esatti, senza ricerca larga e senza ipotesi sul passo.
 *
 * Vive nel campo `UserComment`, che è fatto per questo: testo libero dell'applicazione, che
 * nessun visore tocca. Il formato è nostro e versionato:
 * `LUNAPANO2|id|indice|totale|pan|tilt|fov|scalaPan|scalaTilt`.
 */
data class PanoTag(
    val panoramaId: String,
    val index: Int,
    val count: Int,
    val panDegrees: Float,
    val tiltDegrees: Float,
    val fovDegrees: Float,
    /**
     * La taratura del gimbal in vigore quando lo scatto è stato fatto.
     *
     * Serve a una cosa sola, ma indispensabile: rendere la correzione **ripetibile**. L'unione
     * misura di quanto il gimbal si è mosso davvero e scrive la correzione nel profilo. Se poi
     * si riunissero le stesse foto una seconda volta, senza sapere con che taratura erano state
     * scattate, la correzione si applicherebbe di nuovo sopra sé stessa: 1,31 diventa 1,72, poi
     * 2,25, e il gimbal comincia a mancare i finecorsa.
     *
     * Sapendo invece la taratura dello scatto, la correzione da applicare è
     * `misurata × scattoConQuesta / vigenteAdesso`: la prima volta vale la misura intera, la
     * seconda vale esattamente uno. Le foto scattate prima che questo campo esistesse valgono 1,
     * ed è giusto: prima di oggi la correzione non c'era e la taratura era davvero neutra.
     */
    val panScale: Float = 1f,
    val tiltScale: Float = 1f,
)

object PanoTags {

    private const val MARKER = "LUNAPANO2"

    /** La prima versione del passaporto: senza la taratura, che allora era sempre neutra. */
    private const val MARKER_V1 = "LUNAPANO1"

    /** Scrive il passaporto nel file, riscrivendo l'EXIF sul posto. */
    fun write(file: File, tag: PanoTag): Boolean = runCatching {
        val exif = ExifInterface(file)
        exif.setAttribute(
            ExifInterface.TAG_USER_COMMENT,
            String.format(
                Locale.US,
                "%s|%s|%d|%d|%.3f|%.3f|%.3f|%.5f|%.5f",
                MARKER, tag.panoramaId, tag.index, tag.count,
                tag.panDegrees, tag.tiltDegrees, tag.fovDegrees,
                tag.panScale, tag.tiltScale,
            ),
        )
        exif.saveAttributes()
        true
    }.getOrDefault(false)

    /** Rilegge il passaporto, se c'è e se è nostro. */
    fun read(file: File): PanoTag? = runCatching {
        val comment = ExifInterface(file).getAttribute(ExifInterface.TAG_USER_COMMENT) ?: return null
        val parts = comment.split('|')
        val versione2 = parts.size >= 9 && parts[0] == MARKER
        val versione1 = parts.size == 7 && parts[0] == MARKER_V1
        if (!versione2 && !versione1) return null
        PanoTag(
            panoramaId = parts[1],
            index = parts[2].toInt(),
            count = parts[3].toInt(),
            panDegrees = parts[4].toFloat(),
            tiltDegrees = parts[5].toFloat(),
            fovDegrees = parts[6].toFloat(),
            panScale = if (versione2) parts[7].toFloat() else 1f,
            tiltScale = if (versione2) parts[8].toFloat() else 1f,
        )
    }.getOrNull()
}
