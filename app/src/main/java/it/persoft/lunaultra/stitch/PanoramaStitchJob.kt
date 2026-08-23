package it.persoft.lunaultra.stitch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import it.persoft.lunaultra.media.MediaItem
import it.persoft.lunaultra.media.MediaRepository
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.timelapse.ShotAngle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cosa sta facendo l'unione, per il pannello che la mostra. */
sealed interface StitchUiState {
    data object Idle : StitchUiState
    data class Working(val fraction: Float, val message: String) : StitchUiState
    data class Done(val fileName: String, val report: StitchReport) : StitchUiState
    data class Failed(val reason: String) : StitchUiState
}

/**
 * Dagli scatti appena fatti alla panoramica unita nella galleria del telefono.
 *
 * Il pezzo scomodo è il primo: la camera non dice come ha chiamato il file che ha appena
 * salvato. La risposta al comando di scatto è vuota, e nessuna notifica porta il nome. Quindi
 * l'accoppiamento fra angoli e file si fa per esclusione: si guarda l'elenco dei file prima di
 * cominciare, lo si riguarda alla fine, e i nuovi arrivati sono gli scatti della panoramica,
 * nell'ordine in cui la camera li ha creati — che è l'ordine in cui li ha scattati.
 *
 * È un ragionamento che regge finché nessun altro scatta sulla stessa camera nel frattempo, ed
 * è una condizione che vale sempre: la camera accetta una sola connessione di controllo per
 * volta, quindi mentre la panoramica gira nessun altro può darle ordini.
 *
 * Se i file nuovi non sono tanti quanti gli scatti, l'unione non parte. Meglio dirlo che unire
 * la foto sbagliata al posto giusto: un fotogramma fuori ordine non produce una panoramica
 * storta, ne produce una senza senso.
 */
class PanoramaStitchJob(
    private val context: Context,
    private val media: MediaRepository,
    private val log: EventLog,
) {

    suspend fun run(
        before: List<MediaItem>,
        after: List<MediaItem>,
        angles: List<ShotAngle>,
        horizontalFovDegrees: Float,
        onProgress: (Float, String) -> Unit,
    ): Result<StitchUiState.Done> = withContext(Dispatchers.IO) {
        runCatching {
            val fresh = newPhotos(before, after)
            require(angles.size >= 2) { "La panoramica ha prodotto meno di due scatti" }
            require(fresh.size == angles.size) {
                "Trovate ${fresh.size} foto nuove per ${angles.size} scatti: non so quale sta dove"
            }

            onProgress(0f, "Scarico ${fresh.size} scatti")
            val shots = fresh.mapIndexed { index, item ->
                val file = media.cache(item) { fraction ->
                    onProgress(
                        DOWNLOAD_SHARE * (index + fraction) / fresh.size,
                        "Scarico lo scatto ${index + 1} di ${fresh.size}",
                    )
                }.getOrElse { throw IllegalStateException("Scaricamento di ${item.name} non riuscito: ${it.message}") }
                PanoramaShot(
                    file = file,
                    panDegrees = angles[index].panDegrees,
                    tiltDegrees = angles[index].tiltDegrees,
                    label = "Scatto ${index + 1}",
                )
            }

            val stitcher = PanoramaStitcher { fraction, message ->
                onProgress(DOWNLOAD_SHARE + (1f - DOWNLOAD_SHARE) * fraction, message)
            }
            val outcome = stitcher.stitch(shots, horizontalFovDegrees).getOrThrow()
            val name = save(outcome.bitmap)
            outcome.bitmap.recycle()

            log.info(
                "PANORAMICA UNITA",
                buildString {
                    appendLine("$name · ${outcome.report.canvasWidth}×${outcome.report.canvasHeight} px")
                    appendLine(
                        "%d scatti · copertura %.0f° × %.0f°".format(
                            outcome.report.frames,
                            outcome.report.coverageHorizontalDegrees,
                            outcome.report.coverageVerticalDegrees,
                        ),
                    )
                    outcome.report.refinements.forEach { appendLine(it) }
                    append(
                        "Correzione massima dell'allineamento: %.2f°"
                            .format(outcome.report.worstCorrectionDegrees),
                    )
                },
            )
            StitchUiState.Done(name, outcome.report)
        }.onFailure { log.warn("PANORAMICA NON UNITA", it.message) }
    }

    /**
     * I file comparsi sulla camera fra prima e dopo, nell'ordine in cui li ha creati.
     *
     * L'ordine conta quanto l'insieme: gli angoli sono in ordine di scatto, e i file vanno
     * accoppiati con quelli. L'ora dello scatto è il criterio, con il nome a decidere i pari —
     * i nomi della camera contengono l'orario al secondo, quindi due scatti dentro lo stesso
     * secondo restano comunque nell'ordine giusto.
     */
    private fun newPhotos(before: List<MediaItem>, after: List<MediaItem>): List<MediaItem> {
        val known = before.map { it.path }.toSet()
        return after
            .filterNot { it.isVideo }
            .filterNot { it.path in known }
            .sortedWith(compareBy({ it.takenAtMs }, { it.name }))
    }

    /** Salva nella galleria del telefono, dove le altre app la trovano. */
    private fun save(bitmap: Bitmap): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        val name = "Panorama_Luna_$stamp.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/Luna Ultra")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("La galleria del telefono non ha accettato il file")
        resolver.openOutputStream(uri).use { output ->
            requireNotNull(output) { "Non riesco a scrivere nella galleria" }
            // Qualità alta ma non massima: oltre il novanta per cento un JPEG cresce molto e
            // migliora niente, e una panoramica è già un file grande di suo.
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return name
    }

    private companion object {
        /** Quanta parte dell'avanzamento è scaricamento: il resto è l'unione vera. */
        const val DOWNLOAD_SHARE = 0.35f
        const val JPEG_QUALITY = 92
    }
}
