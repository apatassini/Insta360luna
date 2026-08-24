package it.persoft.lunaultra.stitch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import it.persoft.lunaultra.media.GALLERY_FOLDER
import it.persoft.lunaultra.media.GALLERY_RELATIVE_PATH
import it.persoft.lunaultra.media.GALLERY_ROOT
import it.persoft.lunaultra.media.MediaItem
import it.persoft.lunaultra.media.MediaRepository
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.timelapse.ShotAngle
import kotlinx.coroutines.Dispatchers
import androidx.annotation.RequiresApi
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
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
 * Il pezzo scomodo è il primo: sapere quale foto sta a quale angolo.
 *
 * La strada buona è quella diretta. La risposta della camera al comando di scatto porta il
 * percorso del file appena scritto — `TakePictureResponse { Photo image = 1 }`, `Photo { string
 * uri = 1 }` — quindi ogni angolo si porta dietro il nome della sua foto, e non c'è niente da
 * indovinare: se la camera ne perde qualcuna, gli angoli rimasti restano accoppiati ai file
 * giusti invece di slittare tutti di una posizione.
 *
 * Quando quel percorso non arriva si torna al ripiego: si guarda l'elenco dei file prima di
 * cominciare, lo si riguarda alla fine, e i nuovi arrivati sono gli scatti della panoramica
 * nell'ordine in cui la camera li ha creati. Regge finché nessun altro scatta sulla stessa
 * camera nel frattempo — e non può succedere, perché la camera accetta una sola connessione di
 * controllo per volta — ma richiede che i file nuovi siano tanti quanti gli scatti. Se non lo
 * sono, l'unione non parte: meglio dirlo che unire la foto sbagliata al posto giusto, perché un
 * fotogramma fuori ordine non produce una panoramica storta, ne produce una senza senso.
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
        fillNadir: Boolean = false,
        onProgress: (Float, String) -> Unit,
    ): Result<StitchUiState.Done> = withContext(Dispatchers.IO) {
        runCatching {
            require(angles.size >= 2) { "La panoramica ha prodotto meno di due scatti" }
            val paired = pairByUri(after, angles) ?: pairByArrival(before, after, angles)

            onProgress(0f, "Scarico ${paired.size} scatti")
            // L'identità del panorama finisce nell'EXIF di ogni copia scaricata: id, numero,
            // angoli esatti. Da lì in poi queste foto si riconoscono e si riuniscono da sole,
            // anche fra un mese, senza indovinare più niente.
            val panoramaId = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val shots = paired.mapIndexed { index, (item, angle) ->
                val file = media.cache(item) { fraction ->
                    onProgress(
                        DOWNLOAD_SHARE * (index + fraction) / paired.size,
                        "Scarico lo scatto ${index + 1} di ${paired.size}",
                    )
                }.getOrElse { throw IllegalStateException("Scaricamento di ${item.name} non riuscito: ${it.message}") }
                PanoTags.write(
                    file,
                    PanoTag(
                        panoramaId = panoramaId,
                        index = index + 1,
                        count = paired.size,
                        panDegrees = angle.panDegrees,
                        tiltDegrees = angle.tiltDegrees,
                        fovDegrees = horizontalFovDegrees,
                    ),
                )
                PanoramaShot(
                    file = file,
                    panDegrees = angle.panDegrees,
                    tiltDegrees = angle.tiltDegrees,
                    label = "Scatto ${index + 1}",
                )
            }

            stitchAndSave(shots, horizontalFovDegrees, fillNadir) { fraction, message ->
                onProgress(DOWNLOAD_SHARE + (1f - DOWNLOAD_SHARE) * fraction, message)
            }
        }.onFailure { log.warn("PANORAMICA NON UNITA", it.message) }
    }

    /**
     * Unisce dei file già sul telefono, nell'ordine dato, come una fila orizzontale.
     *
     * È il banco di prova dell'unione: si lavora sugli scatti che ci sono già, senza rifare la
     * panoramica ogni volta. Gli angoli veri non ci sono, quindi si assume quello che una fila
     * di scatti è: passi uguali da sinistra a destra, larghi quanto il campo visivo meno la
     * sovrapposizione scelta. Il resto lo trova l'allineamento, che parte con otto gradi di
     * finestra e i punti di controllo per la rifinitura.
     */
    suspend fun runOnFiles(
        files: List<File>,
        horizontalFovDegrees: Float,
        overlapPercent: Int,
        onProgress: (Float, String) -> Unit,
    ): Result<StitchUiState.Done> = withContext(Dispatchers.IO) {
        runCatching {
            require(files.size >= 2) { "Servono almeno due foto da unire" }

            // Se ogni foto porta il passaporto della stessa panoramica, non c'è niente da
            // indovinare: ordine dai numeri, angoli esatti dai tag, ricerca stretta.
            val tags = files.map { PanoTags.read(it) }
            val sameId = tags.all { it != null } &&
                tags.mapNotNull { it?.panoramaId }.distinct().size == 1
            if (sameId) {
                val ordered = files.zip(tags).sortedBy { it.second!!.index }
                val fov = ordered.first().second!!.fovDegrees.takeIf { it > 10f } ?: horizontalFovDegrees
                val shots = ordered.map { (file, tag) ->
                    PanoramaShot(
                        file = file,
                        panDegrees = tag!!.panDegrees,
                        tiltDegrees = tag.tiltDegrees,
                        label = "Foto ${tag.index}",
                    )
                }
                log.info(
                    "UNIONE MANUALE · FOTO RICONOSCIUTE",
                    "Panorama ${ordered.first().second!!.panoramaId}: ${shots.size} foto con angoli " +
                        "esatti dai tag EXIF. Niente ipotesi, ricerca stretta.",
                )
                return@runCatching stitchAndSave(shots, fov, fillNadir = false, onProgress = onProgress)
            }

            val stepDegrees = horizontalFovDegrees * (1f - overlapPercent.coerceIn(5, 90) / 100f)
            val start = -stepDegrees * (files.size - 1) / 2f
            val shots = files.mapIndexed { index, file ->
                PanoramaShot(
                    file = file,
                    panDegrees = start + index * stepDegrees,
                    tiltDegrees = 0f,
                    label = "Foto ${index + 1}",
                )
            }
            log.info(
                "UNIONE MANUALE",
                "${files.size} foto nell'ordine dato · passo assunto %.1f° (FOV %.1f°, sovrapposizione $overlapPercent%%)"
                    .format(stepDegrees, horizontalFovDegrees),
            )
            stitchAndSave(shots, horizontalFovDegrees, fillNadir = false, wideSearch = true, onProgress = onProgress)
        }.onFailure { log.warn("PANORAMICA NON UNITA", it.message) }
    }

    /** Il tratto comune: unione, salvataggio in galleria, racconto nel log. */
    private suspend fun stitchAndSave(
        shots: List<PanoramaShot>,
        horizontalFovDegrees: Float,
        fillNadir: Boolean,
        wideSearch: Boolean = false,
        onProgress: (Float, String) -> Unit,
    ): StitchUiState.Done {
        val stitcher = PanoramaStitcher(onProgress)
        val outcome = stitcher.stitch(shots, horizontalFovDegrees, fillNadir, wideSearch).getOrThrow()
        val unaligned = outcome.report.refinements.count { it.contains("resta dov'era") }
        if (unaligned > 0) {
            log.warn(
                "GIUNZIONI NON ALLINEATE",
                "$unaligned foto non hanno trovato un combaciamento affidabile e sono rimaste " +
                    "alla posizione ipotizzata: lì la panoramica è incollata a secco.",
            )
        }
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
                if (outcome.report.nadirPatchRows > 0) {
                    appendLine(
                        "Buco sotto chiuso: %d righe inventate a partire dall'ultimo anello buono."
                            .format(outcome.report.nadirPatchRows),
                    )
                }
                outcome.report.refinements.forEach { appendLine(it) }
                append(
                    "Correzione massima dell'allineamento: %.2f°"
                        .format(outcome.report.worstCorrectionDegrees),
                )
            },
        )
        return StitchUiState.Done(name, outcome.report)
    }

    /**
     * L'accoppiamento buono: ogni angolo con il file che la camera ha detto di aver scritto.
     *
     * Vale solo se tutti gli angoli hanno un percorso e tutti i percorsi trovano un file
     * nell'elenco: mezzo accoppiamento è peggio di nessuno, perché sui restanti bisognerebbe
     * comunque indovinare, e a quel punto tanto vale indovinare su tutti con un criterio solo.
     *
     * Il confronto è sul nome del file, non sul percorso intero: la camera nomina la cartella a
     * modo suo nella risposta allo scatto e a modo suo nell'elenco dei file, e i due modi non
     * sempre coincidono. Il nome invece è quello e basta.
     */
    private fun pairByUri(
        after: List<MediaItem>,
        angles: List<ShotAngle>,
    ): List<Pair<MediaItem, ShotAngle>>? {
        if (angles.any { it.uri.isNullOrBlank() }) return null
        val byName = after.filterNot { it.isVideo }.associateBy { it.name }
        val paired = angles.map { angle ->
            val name = angle.uri.orEmpty().substringAfterLast('/')
            val item = byName[name] ?: return null
            item to angle
        }
        log.info(
            "Unione: ${paired.size} scatti accoppiati per nome",
            "La camera ha detto lei quale file ha scritto a ogni angolo.",
        )
        return paired
    }

    /**
     * Il ripiego: i file comparsi sulla camera fra prima e dopo, nell'ordine in cui li ha creati.
     *
     * L'ordine conta quanto l'insieme: gli angoli sono in ordine di scatto, e i file vanno
     * accoppiati con quelli. L'ora dello scatto è il criterio, con il nome a decidere i pari —
     * i nomi della camera contengono l'orario al secondo, quindi due scatti dentro lo stesso
     * secondo restano comunque nell'ordine giusto.
     */
    private fun pairByArrival(
        before: List<MediaItem>,
        after: List<MediaItem>,
        angles: List<ShotAngle>,
    ): List<Pair<MediaItem, ShotAngle>> {
        val known = before.map { it.path }.toSet()
        val fresh = after
            .filterNot { it.isVideo }
            .filterNot { it.path in known }
            .sortedWith(compareBy({ it.takenAtMs }, { it.name }))
        require(fresh.size == angles.size) {
            "Trovate ${fresh.size} foto nuove per ${angles.size} scatti: non so quale sta dove"
        }
        return fresh.zip(angles)
    }

    /**
     * Salva nella galleria del telefono, dove le altre app la trovano.
     *
     * Due strade, perché la galleria non è la stessa cosa su tutte le versioni di Android. Da
     * Android 10 in poi è un archivio a cui si chiede un posto e si scrive dentro senza
     * permessi; prima era una cartella pubblica sulla memoria, e ci si scriveva un file. La
     * colonna che dice in quale sottocartella mettere il file esiste solo dalla decima in poi:
     * usarla comunque non è una regressione elegante, è un errore al momento del salvataggio.
     *
     * Finisce in DCIM › Luna Ultra, la stessa cartella dove vanno le foto scaricate dalla
     * camera: la panoramica unita e gli scatti che l'hanno generata stanno insieme.
     */
    private fun save(bitmap: Bitmap): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        val name = "Panorama_Luna_$stamp.jpg"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(name, bitmap)
        } else {
            saveToPublicDirectory(name, bitmap)
        }
        return name
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(name: String, bitmap: Bitmap) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
            // In sospeso finché non è scritto per intero: senza, la galleria mostrerebbe una
            // miniatura di un file a metà mentre la compressione è ancora in corso.
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("La galleria del telefono non ha accettato il file")
        resolver.openOutputStream(uri).use { output ->
            requireNotNull(output) { "Non riesco a scrivere nella galleria" }
            bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun saveToPublicDirectory(name: String, bitmap: Bitmap) {
        val root = Environment.getExternalStoragePublicDirectory(GALLERY_ROOT)
        val directory = File(root, GALLERY_FOLDER).apply { mkdirs() }
        val target = File(directory, name)
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        // Prima di Android 10 un file appena scritto non compare finché qualcuno non lo
        // segnala: senza questa riga la panoramica esisterebbe ma la galleria non la vedrebbe.
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null)
    }

    private companion object {
        /** Quanta parte dell'avanzamento è scaricamento: il resto è l'unione vera. */
        const val DOWNLOAD_SHARE = 0.35f

        /**
         * Qualità alta ma non massima: oltre il novanta per cento un JPEG cresce molto e
         * migliora niente, e una panoramica è già un file grande di suo.
         */
        const val JPEG_QUALITY = 92
    }
}
