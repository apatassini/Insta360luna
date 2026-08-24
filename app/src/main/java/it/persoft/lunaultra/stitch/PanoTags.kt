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
 * `LUNAPANO1|id|indice|totale|pan|tilt|fov`.
 */
data class PanoTag(
    val panoramaId: String,
    val index: Int,
    val count: Int,
    val panDegrees: Float,
    val tiltDegrees: Float,
    val fovDegrees: Float,
)

object PanoTags {

    private const val MARKER = "LUNAPANO1"

    /** Scrive il passaporto nel file, riscrivendo l'EXIF sul posto. */
    fun write(file: File, tag: PanoTag): Boolean = runCatching {
        val exif = ExifInterface(file)
        exif.setAttribute(
            ExifInterface.TAG_USER_COMMENT,
            String.format(
                Locale.US,
                "%s|%s|%d|%d|%.3f|%.3f|%.3f",
                MARKER, tag.panoramaId, tag.index, tag.count,
                tag.panDegrees, tag.tiltDegrees, tag.fovDegrees,
            ),
        )
        exif.saveAttributes()
        true
    }.getOrDefault(false)

    /** Rilegge il passaporto, se c'è e se è nostro. */
    fun read(file: File): PanoTag? = runCatching {
        val comment = ExifInterface(file).getAttribute(ExifInterface.TAG_USER_COMMENT) ?: return null
        val parts = comment.split('|')
        if (parts.size != 7 || parts[0] != MARKER) return null
        PanoTag(
            panoramaId = parts[1],
            index = parts[2].toInt(),
            count = parts[3].toInt(),
            panDegrees = parts[4].toFloat(),
            tiltDegrees = parts[5].toFloat(),
            fovDegrees = parts[6].toFloat(),
        )
    }.getOrNull()
}
