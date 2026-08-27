package it.persoft.lunaultra.stitch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.media.GALLERY_FOLDER
import it.persoft.lunaultra.media.GALLERY_RELATIVE_PATH
import it.persoft.lunaultra.media.GALLERY_ROOT
import it.persoft.lunaultra.media.MediaItem
import it.persoft.lunaultra.media.MediaRepository
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.timelapse.ShotAngle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import androidx.annotation.RequiresApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Una foto scelta dal telefono: come si chiama e come se ne fa una copia.
 *
 * Il nome serve al log e all'ordine a parità di istante; la copia la sa fare solo chi ha in
 * mano il `ContentResolver`, che sta nell'interfaccia. Qui dentro non entra nessun `Uri`.
 */
class PickedPhoto(val name: String, val copyInto: (File) -> Unit)

/** Cosa sta facendo l'unione, per il pannello che la mostra. */
sealed interface StitchUiState {
    data object Idle : StitchUiState
    data class Working(val fraction: Float, val message: String) : StitchUiState
    data class Done(val fileName: String, val report: StitchReport) : StitchUiState
    data class Failed(val reason: String) : StitchUiState

    /** Scatti scaricati e messi da parte: l'unione partirà quando la si lancia dai job. */
    data class Queued(val jobId: String, val count: Int) : StitchUiState
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
    private val locations: it.persoft.lunaultra.media.LocationDiary? = null,
    /**
     * Che fare della taratura misurata sulle foto.
     *
     * L'unione misura, come effetto secondario, di quanto il gimbal si è mosso davvero rispetto
     * a quanto gli era stato chiesto. Correggere gli angoli qui rimette a posto *questa*
     * panoramica; scrivere la misura nel profilo del gimbal rimette a posto anche la
     * sovrapposizione dei prossimi scatti, che altrimenti resta più stretta di quella impostata.
     *
     * Passa di qui e non dal pacchetto `stitch` perché lo stitcher non deve sapere che esiste un
     * gimbal con un profilo salvato: lui misura e lo dice, decide chi lo ha chiamato.
     */
    private val onGimbalScale: (pan: Float, tilt: Float) -> Unit = { _, _ -> },
    /** La taratura del gimbal in vigore adesso: si scrive nei tag e serve a non correggere due volte. */
    private val gimbalScaleNow: () -> Pair<Float, Float> = { 1f to 1f },
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
                        panScale = gimbalScaleNow().first,
                        tiltScale = gimbalScaleNow().second,
                    ),
                )
                val attitude = InstaTrailer.readAttitude(file)
                PanoramaShot(
                    file = file,
                    panDegrees = angle.panDegrees,
                    tiltDegrees = angle.tiltDegrees,
                    label = "Scatto ${index + 1}",
                    measuredTiltDegrees = attitude?.pitchDegrees,
                    measuredRollDegrees = attitude?.rollDegrees,
                )
            }

            stitchAndSave(
                shots,
                horizontalFovDegrees,
                fillNadir,
                scaleAtShot = gimbalScaleNow(),
            ) { fraction, message ->
                onProgress(DOWNLOAD_SHARE + (1f - DOWNLOAD_SHARE) * fraction, message)
            }
        }.onFailure { log.warn("PANORAMICA NON UNITA", it.message) }
    }

    /**
     * Scarica e marca gli scatti di una panoramica appena fatta, senza unirli: il job aspetta.
     *
     * L'unione sono minuti di calcolo e può aspettare la sera; lo scaricamento no, va fatto
     * finché la camera è lì. Gli scatti finiscono in `DCIM › Luna Ultra › Panoramiche/<id>`,
     * ognuno con il passaporto negli EXIF (id, ordine, angoli esatti): da quel momento il job
     * si unisce da solo quando lo si lancia, anche dopo un riavvio dell'app.
     */
    suspend fun collectForJob(
        before: List<MediaItem>,
        after: List<MediaItem>,
        angles: List<ShotAngle>,
        horizontalFovDegrees: Float,
        panoramaId: String,
        onProgress: (Float, String) -> Unit,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            require(angles.size >= 2) { "La panoramica ha prodotto meno di due scatti" }
            val paired = pairByUri(after, angles) ?: pairByArrival(before, after, angles)
            val dir = jobDirFor(panoramaId)
            // Tutto il lavoro sporco — file parziale, riscrittura EXIF — avviene in una
            // cartella privata dell'app: in DCIM Android nega i nomi non-media (il «.part»
            // dello scaricamento falliva con EPERM) e anche i file d'appoggio dell'EXIF.
            // Nella cartella pubblica arriva una sola copia, già finita, col suo nome .jpg.
            val workshop = File(context.cacheDir, "panojob").apply { mkdirs() }
            val files = paired.mapIndexed { index, (item, angle) ->
                val draft = File(workshop, item.name)
                media.downloadInto(item, draft) { fraction ->
                    onProgress(
                        (index + fraction) / paired.size,
                        "Scarico lo scatto ${index + 1} di ${paired.size}",
                    )
                }.getOrElse { throw IllegalStateException("Scaricamento di ${item.name} non riuscito: ${it.message}") }
                PanoTags.write(
                    draft,
                    PanoTag(
                        panoramaId = panoramaId,
                        index = index + 1,
                        count = paired.size,
                        panDegrees = angle.panDegrees,
                        tiltDegrees = angle.tiltDegrees,
                        fovDegrees = horizontalFovDegrees,
                        panScale = gimbalScaleNow().first,
                        tiltScale = gimbalScaleNow().second,
                    ),
                )
                locations?.stampFile(draft, item.takenAtMs)
                val stored = storeInJobDirectory(draft, item.name, dir, panoramaId)
                draft.delete()
                stored
            }
            MediaScannerConnection.scanFile(
                context,
                files.map { it.absolutePath }.toTypedArray(),
                null,
                null,
            )
            log.info(
                "PANORAMICA MESSA IN CODA",
                "${files.size} scatti in ${dir.path}. L'unione parte dalla scheda dei job, quando si vuole.",
            )
            files
        }.onFailure { log.warn("PANORAMICA NON MESSA IN CODA", it.message) }
    }

    /**
     * Mette in coda delle foto **scelte a mano** dalla galleria della camera.
     *
     * È il banco di prova voluto: un gruppo di scatti difficili — il bambù di lato, la
     * controluce, il muro vicino — si scarica una volta sola e resta lì come job. Da quel
     * momento la stessa terna si può riunire quante volte si vuole, provando ricette diverse
     * senza rifare né gli scatti né lo scaricamento.
     *
     * A differenza di una panoramica pianificata, qui gli angoli veri non ci sono e non si
     * scrive nessun passaporto EXIF: sarebbe un angolo inventato spacciato per misura, e
     * l'unione gli crederebbe. Senza passaporto l'unione sa di dover indovinare il passo e
     * cerca largo, che è la cosa giusta da fare con delle foto raccolte a mano.
     */
    suspend fun collectChosenForJob(
        items: List<MediaItem>,
        panoramaId: String,
        onProgress: (Float, String) -> Unit,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            require(items.size >= 2) { "Servono almeno due foto per un job di unione" }
            val dir = jobDirFor(panoramaId)
            val workshop = File(context.cacheDir, "panojob").apply { mkdirs() }
            val files = items.mapIndexed { index, item ->
                val draft = File(workshop, item.name)
                media.downloadInto(item, draft) { fraction ->
                    onProgress(
                        (index + fraction) / items.size,
                        "Scarico la foto ${index + 1} di ${items.size}",
                    )
                }.getOrElse { throw IllegalStateException("Scaricamento di ${item.name} non riuscito: ${it.message}") }
                locations?.stampFile(draft, item.takenAtMs)
                val stored = storeInJobDirectory(draft, item.name, dir, panoramaId)
                draft.delete()
                stored
            }
            MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null)
            log.info(
                "JOB DI PROVA CREATO",
                "${files.size} foto scelte a mano in ${dir.path}. Nessun angolo dichiarato: " +
                    "l'unione assume una fila e cerca largo. Si rilancia quante volte si vuole.",
            )
            files
        }.onFailure { log.warn("JOB DI PROVA NON CREATO", it.message) }
    }

    /**
     * Un job dalle foto che stanno già sul telefono, messe **in ordine di scatto**.
     *
     * L'ordine non si chiede all'utente e non si prende dal selettore: il selettore di sistema
     * restituisce quello che gli pare — l'ordine dei tocchi su alcuni gestori di file, quello
     * alfabetico su altri — e sbagliarlo significa disporre le foto a ventaglio nel posto
     * sbagliato, perché è la posizione nella fila a decidere il pan assunto.
     *
     * L'ordine vero ce l'hanno le foto addosso: l'istante dello scatto negli EXIF, al
     * millesimo quando c'è il campo dei sottomultipli. Chi non ce l'ha ripiega sulla data del
     * file, e a parità di tutto sul nome.
     */
    suspend fun collectPickedForJob(
        sources: List<PickedPhoto>,
        panoramaId: String,
        onProgress: (Float, String) -> Unit,
    ): Result<List<File>> = withContext(Dispatchers.IO) {
        runCatching {
            require(sources.size >= 2) { "Servono almeno due foto per un job di unione" }
            val workshop = File(context.cacheDir, "panopick").apply { mkdirs() }
            workshop.listFiles()?.forEach { it.delete() }

            // Prima si copiano — un URI non si rilegge due volte con la stessa tranquillità —
            // e poi si guarda dentro per sapere quando sono state scattate.
            //
            // In parallelo: copiare è aspettare il disco, non calcolare, e nove foto da otto
            // megabyte una dopo l'altra sono nove attese messe in fila per niente. A quattro
            // per volta il disco lavora mentre l'altra copia si chiude, e il conto
            // dell'avanzamento sale a ogni foto finita, non a ogni foto cominciata.
            val done = java.util.concurrent.atomic.AtomicInteger(0)
            val drafts = coroutineScope {
                sources.withIndex().chunked(COPY_BATCH).flatMap { batch ->
                    batch.map { (index, source) ->
                        async(Dispatchers.IO) {
                            currentCoroutineContext().ensureActive()
                            val draft = File(workshop, "scelta-%03d.jpg".format(index))
                            source.copyInto(draft)
                            check(draft.length() > 0L) { "la foto ${index + 1} è arrivata vuota" }
                            onProgress(
                                done.incrementAndGet() / sources.size.toFloat(),
                                "Copio le foto (${done.get()} di ${sources.size})",
                            )
                            draft to source.name
                        }
                    }.awaitAll()
                }
            }

            val ordered = drafts
                .map { (draft, name) -> Triple(draft, name, captureInstant(draft)) }
                .sortedWith(compareBy({ it.third }, { it.second }))

            val dir = jobDirFor(panoramaId)
            val files = ordered.mapIndexed { position, (draft, name, _) ->
                // Il nome porta la posizione: la cartella del job si legge in ordine, e il
                // nome originale resta lì accanto per ritrovare la foto di partenza.
                val stored = storeInJobDirectory(draft, "%02d-%s".format(position + 1, name), dir, panoramaId)
                draft.delete()
                stored
            }
            MediaScannerConnection.scanFile(context, files.map { it.absolutePath }.toTypedArray(), null, null)
            log.info(
                "JOB DA FOTO DEL TELEFONO",
                "${files.size} foto messe in ordine di scatto: " +
                    ordered.joinToString(" · ") { (_, name, instant) ->
                        "$name (${SimpleDateFormat("HH:mm:ss", Locale.ITALIAN).format(Date(instant))})"
                    } + ". In ${dir.path}.",
            )
            files
        }.onFailure { log.warn("JOB DA FOTO DEL TELEFONO NON CREATO", it.message) }
    }

    /**
     * Quando è stata scattata una foto, al millesimo quando si può saperlo.
     *
     * L'EXIF porta la data dello scatto con la precisione del secondo, e su una panoramica
     * scattata a mano due foto nello stesso secondo capitano: per quelle c'è
     * `SubSecTimeOriginal`, i decimi e centesimi che l'apparecchio scrive a parte. Senza EXIF
     * resta la data del file, che è quella della copia — buona solo a non cambiare l'ordine.
     */
    private fun captureInstant(file: File): Long {
        val exif = runCatching { ExifInterface(file) }.getOrNull() ?: return file.lastModified()
        val stamp = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return file.lastModified()
        val seconds = runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(stamp)?.time
        }.getOrNull() ?: return file.lastModified()
        val subSec = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL)
            ?.take(3)?.padEnd(3, '0')?.toLongOrNull() ?: 0L
        return seconds + subSec.coerceIn(0L, 999L)
    }

    /**
     * La copia finale nella cartella del job, con il ripiego sulla memoria dell'app.
     *
     * In DCIM alcuni Android negano la scrittura: allora il job vive nella cartella privata.
     * Meno in vista, ma vive — ed è meglio di un job che non nasce.
     */
    private fun storeInJobDirectory(draft: File, name: String, dir: File, panoramaId: String): File {
        val target = File(dir, name)
        return runCatching {
            draft.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            check(target.length() == draft.length()) {
                "copia incompleta (${target.length()} su ${draft.length()} byte)"
            }
            target
        }.getOrElse { denied ->
            log.warn("Copia di $name in ${dir.path} negata: ${denied.message}", "Uso la memoria dell'app.")
            runCatching { target.delete() }
            val privateDir = File(context.getExternalFilesDir(null), "$JOB_FOLDER/$panoramaId")
                .apply { mkdirs() }
            draft.copyTo(File(privateDir, name), overwrite = true)
        }
    }

    /**
     * La cartella degli scatti di un job: visibile e fuori dalla cache, perché deve durare.
     *
     * Prima scelta `DCIM › Luna Ultra › Panoramiche/<id>` — dove l'utente la vede e la
     * ritrova. Su Android 10, l'unica versione che nega la scrittura diretta in DCIM, si
     * ripiega sulla memoria esterna dell'app: il job funziona uguale, solo meno in vista.
     */
    private fun jobDirFor(panoramaId: String): File {
        val public = File(
            File(Environment.getExternalStoragePublicDirectory(GALLERY_ROOT), GALLERY_FOLDER),
            "$JOB_FOLDER/$panoramaId",
        )
        if (public.isDirectory || public.mkdirs()) return public
        val fallback = File(context.getExternalFilesDir(null), "$JOB_FOLDER/$panoramaId")
        fallback.mkdirs()
        log.warn("Cartella pubblica dei job non scrivibile: uso ${fallback.path}")
        return fallback
    }

    /**
     * Butta via gli scatti temporanei di un job riuscito, e la loro cartella se resta vuota.
     *
     * Si chiama solo a panoramica salvata: finché l'unione non è andata a buon fine gli
     * scatti non si toccano, e un job annullato li lascia dove sono.
     */
    fun discardJobFiles(paths: List<String>) {
        val files = paths.map(::File)
        files.forEach { runCatching { it.delete() } }
        files.mapNotNull { it.parentFile }.distinct().forEach { dir ->
            if (dir.list()?.isEmpty() == true) runCatching { dir.delete() }
        }
        // La galleria di sistema va avvisata: senza, mostrerebbe miniature di file spariti.
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
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
        fillNadir: Boolean = false,
        shotAtMs: Long = System.currentTimeMillis(),
        tuning: StitchTuning = StitchTuning(),
        testMode: Boolean = false,
        /** La fase intermedia: scegliere da dove guardare la panoramica, guardandola. */
        onPreview: (suspend (PanoramaPreview) -> AfterPreview)? = null,
        /** Il punto di vista gia` scelto per questo lavoro, se qualcuno l'ha scelto. */
        view: PanoramaView? = null,
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
                    val attitude = InstaTrailer.readAttitude(file)
                    PanoramaShot(
                        file = file,
                        panDegrees = tag!!.panDegrees,
                        tiltDegrees = tag.tiltDegrees,
                        label = "Foto ${tag.index}",
                        measuredTiltDegrees = attitude?.pitchDegrees,
                        measuredRollDegrees = attitude?.rollDegrees,
                    )
                }
                log.info(
                    "UNIONE MANUALE · FOTO RICONOSCIUTE",
                    "Panorama ${ordered.first().second!!.panoramaId}: ${shots.size} foto con angoli " +
                        "esatti dai tag EXIF. Niente ipotesi, ricerca stretta.",
                )
                return@runCatching if (testMode) {
                    stitchTestVariants(shots, fov, wideSearch = false, shotAtMs = shotAtMs, base = tuning, onProgress = onProgress)
                } else {
                    stitchAndSave(
                        shots,
                        fov,
                        fillNadir = fillNadir,
                        shotAtMs = shotAtMs,
                        tuning = tuning,
                        scaleAtShot = ordered.first().second!!.let { it.panScale to it.tiltScale },
                        onPreview = onPreview,
                        view = view,
                        onProgress = onProgress,
                    )
                }
            }

            // Niente passaporto EXIF: sono foto qualsiasi, prese dal telefono o da una
            // galleria. Ma se a scattarle è stata la Luna, in coda al file c'è lo stesso la
            // traccia inerziale — ed è la metà più importante di quello che avrebbe detto il
            // gimbal. L'inclinazione e il rollio arrivano dalla gravità, che non si sbaglia e
            // non ha bisogno di taratura; resta da indovinare solo il pan.
            val attitudes = files.map { InstaTrailer.readAttitude(it) }
            val known = attitudes.count { it != null }
            val shots = if (known == files.size) {
                shotsFromAttitude(files, attitudes.map { it!! }, horizontalFovDegrees, overlapPercent)
            } else {
                if (known > 0) {
                    log.info(
                        "UNIONE MANUALE",
                        "Solo $known foto su ${files.size} portano la traccia inerziale: " +
                            "non basta a dividerle in file, si assume una fila sola.",
                    )
                }
                evenRow(files, horizontalFovDegrees, overlapPercent)
            }
            if (testMode) {
                stitchTestVariants(shots, horizontalFovDegrees, wideSearch = true, shotAtMs = shotAtMs, base = tuning, onProgress = onProgress)
            } else {
                stitchAndSave(
                    shots, horizontalFovDegrees, fillNadir = false, wideSearch = true,
                    shotAtMs = shotAtMs, tuning = tuning, onPreview = onPreview, view = view,
                    onProgress = onProgress,
                )
            }
        }.onFailure { log.warn("PANORAMICA NON UNITA", it.message) }
    }

    /** Il tratto comune: unione, salvataggio in galleria, racconto nel log. */
    /**
     * Le foto disposte a righe, leggendo dove guardava la camera nella coda del file.
     *
     * Le foto di una panoramica si scattano quasi sempre una fila per volta, e fra una fila e
     * l'altra l'inclinazione fa un salto. Quel salto si vede: basta guardare l'inclinazione
     * misurata dalla gravità e tagliare dove cambia di più di un terzo del campo visivo. Dentro
     * ogni fila il pan resta indovinato — la camera la sua rotazione attorno alla verticale non
     * la dice, e nemmeno la gravità — ma indovinarlo su tre foto per fila invece che su nove di
     * seguito è tutta un'altra cosa: la ricerca larga parte già vicina, e la griglia non viene
     * srotolata in una striscia.
     */
    private fun shotsFromAttitude(
        files: List<File>,
        attitudes: List<ShotAttitude>,
        horizontalFovDegrees: Float,
        overlapPercent: Int,
    ): List<PanoramaShot> {
        val stepDegrees = horizontalFovDegrees * (1f - overlapPercent.coerceIn(5, 90) / 100f)
        val split = horizontalFovDegrees * ROW_SPLIT_SHARE

        // Le file, nell'ordine in cui sono state scattate: si taglia dove l'inclinazione salta.
        val rows = mutableListOf<MutableList<Int>>()
        var reference = attitudes.first().pitchDegrees
        var current = mutableListOf<Int>()
        for (index in files.indices) {
            val pitch = attitudes[index].pitchDegrees
            if (current.isNotEmpty() && kotlin.math.abs(pitch - reference) > split) {
                rows += current
                current = mutableListOf()
            }
            if (current.isEmpty()) reference = pitch
            current += index
        }
        if (current.isNotEmpty()) rows += current

        val shots = arrayOfNulls<PanoramaShot>(files.size)
        for (row in rows) {
            val start = -stepDegrees * (row.size - 1) / 2f
            row.forEachIndexed { position, index ->
                shots[index] = PanoramaShot(
                    file = files[index],
                    panDegrees = start + position * stepDegrees,
                    tiltDegrees = attitudes[index].pitchDegrees,
                    label = "Foto ${index + 1}",
                    measuredTiltDegrees = attitudes[index].pitchDegrees,
                    measuredRollDegrees = attitudes[index].rollDegrees,
                )
            }
        }
        log.info(
            "UNIONE MANUALE · LETTA DALLE FOTO",
            "${files.size} foto in ${rows.size} " +
                (if (rows.size == 1) "fila" else "file") +
                " (${rows.joinToString(" · ") { "${it.size}" }}), inclinazione e rollio dalla " +
                "gravità: %s. Il pan resta assunto, passo %.1f°.".format(
                    rows.joinToString(" · ") { row -> "%+.0f°".format(attitudes[row.first()].pitchDegrees) },
                    stepDegrees,
                ),
        )
        return shots.filterNotNull()
    }

    /** Il ripiego di sempre: una fila sola, passo assunto dal campo visivo e dalla sovrapposizione. */
    private fun evenRow(
        files: List<File>,
        horizontalFovDegrees: Float,
        overlapPercent: Int,
    ): List<PanoramaShot> {
        val stepDegrees = horizontalFovDegrees * (1f - overlapPercent.coerceIn(5, 90) / 100f)
        val start = -stepDegrees * (files.size - 1) / 2f
        log.info(
            "UNIONE MANUALE",
            "${files.size} foto nell'ordine dato · passo assunto %.1f° (FOV %.1f°, sovrapposizione $overlapPercent%%)"
                .format(stepDegrees, horizontalFovDegrees),
        )
        return files.mapIndexed { index, file ->
            PanoramaShot(
                file = file,
                panDegrees = start + index * stepDegrees,
                tiltDegrees = 0f,
                label = "Foto ${index + 1}",
            )
        }
    }

    private suspend fun stitchAndSave(
        shots: List<PanoramaShot>,
        horizontalFovDegrees: Float,
        fillNadir: Boolean,
        wideSearch: Boolean = false,
        shotAtMs: Long = System.currentTimeMillis(),
        tuning: StitchTuning = StitchTuning(),
        /** Con che taratura erano state scattate queste foto; null se non lo sappiamo. */
        scaleAtShot: Pair<Float, Float>? = null,
        /** La fase intermedia, se c'è chi la sa mostrare. Senza, la cucitura va dritta. */
        onPreview: (suspend (PanoramaPreview) -> AfterPreview)? = null,
        /** Il punto di vista già scelto: si cuce e basta, senza fermarsi a chiedere. */
        view: PanoramaView? = null,
        onProgress: (Float, String) -> Unit,
    ): StitchUiState.Done {
        // La memoria si misura adesso, non all'avvio: quanta ne sia libera dipende da cosa
        // stava facendo il telefono un minuto fa.
        val stitcher = PanoramaStitcher(onProgress, tuning, MemoryBudget.measure(context))
        val outcome = stitcher
            .stitch(shots, horizontalFovDegrees, fillNadir, wideSearch, onPreview, view)
            .getOrThrow()
        // La correzione da girare al profilo del gimbal, resa ripetibile.
        //
        // Quello che l'unione misura è quanto il gimbal ha sbagliato **con la taratura di
        // allora**. Se da allora la taratura è cambiata — perché una prima unione l'aveva già
        // corretta — riapplicare la misura intera vorrebbe dire correggere due volte lo stesso
        // errore. Riunendo tre volte le stesse foto, 1,31 diventerebbe 2,25 e il gimbal
        // comincerebbe a mancare i finecorsa.
        //
        // Il rapporto fra la taratura di allora e quella di adesso rimette le cose a posto: la
        // prima volta vale uno e passa la misura intera, la seconda vale l'inverso della misura
        // e la annulla. Senza sapere con che taratura erano state scattate — foto vecchie, senza
        // il campo nel tag — non si tocca niente e ci si limita a raccontarlo nel verdetto.
        val panScale = outcome.report.gimbalScalePan
        val tiltScale = outcome.report.gimbalScaleTilt
        // Un asse alla volta: la gravità può dare il verticale anche quando le immagini non
        // danno l'orizzontale, e viceversa. L'asse non misurato passa con fattore uno, che
        // significa «questo lascialo com'è».
        if ((panScale != null || tiltScale != null) && scaleAtShot != null) {
            val (panNow, tiltNow) = gimbalScaleNow()
            onGimbalScale(
                panScale?.let {
                    GimbalCalibrationProfile.repeatableCorrection(it, scaleAtShot.first, panNow)
                } ?: 1f,
                tiltScale?.let {
                    GimbalCalibrationProfile.repeatableCorrection(it, scaleAtShot.second, tiltNow)
                } ?: 1f,
            )
        }
        val unaligned = outcome.report.refinements.count { it.contains("resta dov'era") }
        if (unaligned > 0) {
            log.warn(
                "GIUNZIONI NON ALLINEATE",
                "$unaligned foto non hanno trovato un combaciamento affidabile e sono rimaste " +
                    "alla posizione ipotizzata: lì la panoramica è incollata a secco.",
            )
        }
        val name = save(outcome.bitmap, shotAtMs, sources = shots.map { it.file })
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
                if (outcome.report.verdict.isNotEmpty()) {
                    appendLine("--- verdetto ---")
                    outcome.report.verdict.forEach { appendLine(it) }
                    appendLine("---")
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
     * Il banco di prova dell'unione: la stessa terna di foto, tutte le ricette in fila.
     *
     * Ogni ricetta lavora a [StitchTestLab.TEST_WORKING_LONG_SIDE] px sul lato lungo e
     * campiona dalla copia di lavoro: piccola e veloce apposta. Ogni risultato finisce in
     * galleria come `Panorama_TEST_<lettera>_…`, così si confrontano fianco a fianco e la
     * lettera dice quale ricetta è. Una ricetta che fallisce non ferma le altre: si annota
     * nel log e si passa avanti.
     */
    private suspend fun stitchTestVariants(
        shots: List<PanoramaShot>,
        horizontalFovDegrees: Float,
        wideSearch: Boolean,
        shotAtMs: Long,
        base: StitchTuning,
        onProgress: (Float, String) -> Unit,
    ): StitchUiState.Done {
        val trio = shots.take(StitchTestLab.TEST_FRAMES)
        val variants = StitchTestLab.variants(base)
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        var saved = 0
        var lastReport: StitchReport? = null
        log.info(
            "MODALITÀ TEST UNIONE",
            "${trio.size} foto a ${StitchTestLab.TEST_WORKING_LONG_SIDE} px · ${variants.size} ricette:\n" +
                variants.joinToString("\n") { "${it.letter}) ${it.title}" },
        )
        for ((index, variant) in variants.withIndex()) {
            currentCoroutineContext().ensureActive()
            val header = "Prova ${variant.letter} di ${variants.size}"
            onProgress(index.toFloat() / variants.size, "$header — ${variant.title}")
            val outcome = PanoramaStitcher(
                onProgress = { fraction, message ->
                    onProgress((index + fraction) / variants.size, "$header: $message")
                },
                tuning = variant.tuning,
                memory = MemoryBudget.measure(context),
            ).stitch(trio, horizontalFovDegrees, fillNadir = false, wideSearch = wideSearch)
            outcome.onSuccess { out ->
                val name = save(
                    out.bitmap,
                    shotAtMs,
                    name = "Panorama_TEST_${variant.letter}_$stamp.jpg",
                    sources = trio.map { it.file },
                )
                out.bitmap.recycle()
                saved++
                lastReport = out.report
                log.info(
                    "PROVA ${variant.letter} · ${variant.title}",
                    buildString {
                        appendLine("$name · ${out.report.canvasWidth}×${out.report.canvasHeight} px")
                        out.report.verdict.forEach { appendLine(it) }
                        out.report.refinements.forEach { appendLine(it) }
                        append("Correzione massima: %.2f°".format(out.report.worstCorrectionDegrees))
                    },
                )
            }.onFailure {
                currentCoroutineContext().ensureActive()
                log.warn("PROVA ${variant.letter} NON RIUSCITA", "${variant.title} · ${it.message}")
            }
        }
        val report = lastReport
            ?: throw IllegalStateException("Nessuna delle ${variants.size} ricette di prova è riuscita: guarda il log")
        onProgress(1f, "Prove pronte: $saved in galleria")
        return StitchUiState.Done(
            "$saved prove in galleria (Panorama_TEST_A…${variants.last().letter})",
            report,
        )
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
    /**
     * Il passaporto degli scatti: dove e quando è stata fatta davvero la panoramica.
     *
     * Una panoramica non nasce nel momento in cui il telefono la cuce — può essere la sera, o
     * il giorno dopo — ma nel momento e nel posto in cui sono stati fatti gli scatti. Quei
     * dati esistono già, scritti nell'EXIF delle foto sorgenti dalla camera: copiarli da lì è
     * più giusto che ricostruirli, perché sono la misura fatta sul posto invece di una stima.
     *
     * La data si prende dal **primo** scatto, che è l'istante in cui la panoramica è
     * cominciata. La posizione invece dal primo scatto che ce l'ha: se la camera ha agganciato
     * il satellite solo a metà sequenza, quella è comunque la posizione giusta, mentre non
     * averne nessuna sarebbe solo una perdita.
     */
    private fun sourcePassport(sources: List<File>): Map<String, String> {
        val passport = mutableMapOf<String, String>()
        val readers = sources.mapNotNull { file ->
            runCatching { androidx.exifinterface.media.ExifInterface(file) }.getOrNull()
        }
        if (readers.isEmpty()) return passport

        readers.first().let { first ->
            TIME_TAGS.forEach { tag -> first.getAttribute(tag)?.let { passport[tag] = it } }
        }
        // La posizione: il primo che ne ha una completa vince, e si prende in blocco — mezza
        // coordinata senza il suo emisfero è peggio di nessuna coordinata.
        readers.firstOrNull { it.latLong != null }?.let { located ->
            LOCATION_TAGS.forEach { tag -> located.getAttribute(tag)?.let { passport[tag] = it } }
        }
        return passport
    }

    /** Scrive il passaporto sul file già compresso, senza toccare il resto dell'EXIF. */
    private fun applyPassport(
        exif: androidx.exifinterface.media.ExifInterface,
        passport: Map<String, String>,
    ): Boolean {
        if (passport.isEmpty()) return false
        passport.forEach { (tag, value) -> exif.setAttribute(tag, value) }
        return true
    }

    /** Il momento dichiarato dagli scatti, se lo dichiarano: è quello che la galleria deve mostrare. */
    private fun passportTimeMs(sources: List<File>): Long? = sources.firstNotNullOfOrNull { file ->
        runCatching { androidx.exifinterface.media.ExifInterface(file).dateTimeOriginal }.getOrNull()
    }

    private fun save(
        bitmap: Bitmap,
        shotAtMs: Long,
        name: String? = null,
        sources: List<File> = emptyList(),
    ): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ITALY).format(Date())
        @Suppress("NAME_SHADOWING")
        val name = name ?: "Panorama_Luna_$stamp.jpg"
        val passport = sourcePassport(sources)
        // Il momento vero della panoramica: quello scritto negli scatti se c'è, altrimenti
        // quello che il job si porta dietro.
        val takenAtMs = passportTimeMs(sources) ?: shotAtMs
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveToMediaStore(name, bitmap, takenAtMs, passport)
        } else {
            saveToPublicDirectory(name, bitmap, takenAtMs, passport)
        }
        return name
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(
        name: String,
        bitmap: Bitmap,
        takenAtMs: Long,
        passport: Map<String, String>,
    ) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, GALLERY_RELATIVE_PATH)
            // La galleria ordina per questa, non per l'EXIF: senza, una panoramica cucita
            // stasera da scatti di ieri finirebbe in cima all'album di stasera.
            put(MediaStore.Images.Media.DATE_TAKEN, takenAtMs)
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
        // Data, ora e posizione della panoramica vengono dagli scatti che la compongono: il
        // job può girare giorni dopo, ma il momento e il posto sono i loro. Il diario delle
        // posizioni del telefono resta il ripiego per quando le foto non portano coordinate.
        runCatching {
            resolver.openFileDescriptor(uri, "rw")?.use { descriptor ->
                val exif = androidx.exifinterface.media.ExifInterface(descriptor.fileDescriptor)
                var touched = applyPassport(exif, passport)
                if (exif.latLong == null && locations?.stamp(exif, takenAtMs) == true) touched = true
                if (touched) exif.saveAttributes()
            }
        }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
    }

    private fun saveToPublicDirectory(
        name: String,
        bitmap: Bitmap,
        takenAtMs: Long,
        passport: Map<String, String>,
    ) {
        val root = Environment.getExternalStoragePublicDirectory(GALLERY_ROOT)
        val directory = File(root, GALLERY_FOLDER).apply { mkdirs() }
        val target = File(directory, name)
        FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
        runCatching {
            val exif = androidx.exifinterface.media.ExifInterface(target)
            var touched = applyPassport(exif, passport)
            if (exif.latLong == null && locations?.stamp(exif, takenAtMs) == true) touched = true
            if (touched) exif.saveAttributes()
        }
        // Anche la data del file segue gli scatti: chi ordina per data di modifica — un
        // gestore di file, un backup — deve vedere la stessa cosa che vede la galleria.
        runCatching { target.setLastModified(takenAtMs) }
        // Prima di Android 10 un file appena scritto non compare finché qualcuno non lo
        // segnala: senza questa riga la panoramica esisterebbe ma la galleria non la vedrebbe.
        MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null)
    }

    private companion object {
        /**
         * Quante foto si copiano insieme dal telefono.
         *
         * Copiare è aspettare il disco: quattro attese sovrapposte tengono occupato il
         * dispositivo senza chiedergli più code di quante ne sappia servire, e senza tenere
         * aperti troppi flussi insieme su un telefono che nel frattempo fa altro.
         */
        const val COPY_BATCH = 4

        /**
         * I tag del tempo, copiati dal primo scatto: è l'istante in cui la panoramica è
         * cominciata. Sono cinque perché una data senza il suo fuso, o senza i decimi, è una
         * data che due programmi diversi leggono in due momenti diversi.
         */
        val TIME_TAGS = listOf(
            androidx.exifinterface.media.ExifInterface.TAG_DATETIME,
            androidx.exifinterface.media.ExifInterface.TAG_DATETIME_ORIGINAL,
            androidx.exifinterface.media.ExifInterface.TAG_DATETIME_DIGITIZED,
            androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME,
            androidx.exifinterface.media.ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            androidx.exifinterface.media.ExifInterface.TAG_SUBSEC_TIME,
            androidx.exifinterface.media.ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
        )

        /**
         * I tag della posizione, copiati in blocco dal primo scatto che ne ha una: una
         * latitudine senza il suo emisfero finisce dall'altra parte del mondo.
         */
        val LOCATION_TAGS = listOf(
            androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_LATITUDE_REF,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_LONGITUDE_REF,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_ALTITUDE_REF,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_TIMESTAMP,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_DATESTAMP,
            androidx.exifinterface.media.ExifInterface.TAG_GPS_PROCESSING_METHOD,
        )

        /** La sottocartella degli scatti in attesa: separa i job dalle foto scaricate. */
        const val JOB_FOLDER = "Panoramiche"

        /** Quanta parte dell'avanzamento è scaricamento: il resto è l'unione vera. */
        const val DOWNLOAD_SHARE = 0.35f

        /**
         * Qualità alta ma non massima: oltre il novanta per cento un JPEG cresce molto e
         * migliora niente, e una panoramica è già un file grande di suo.
         */
        const val JPEG_QUALITY = 92

        /**
         * Quanto deve saltare l'inclinazione perché sia una fila nuova, in frazione di campo.
         *
         * Un terzo del campo visivo: dentro una fila l'inclinazione non si muove di più di
         * qualche grado — la mano trema, non alza la camera — mentre fra una fila e l'altra il
         * salto vale piu` di mezzo campo, altrimenti le due file non si sovrapporrebbero
         * abbastanza da poter essere unite. In mezzo c'e` un abisso, e la soglia ci sta comoda.
         */
        const val ROW_SPLIT_SHARE = 0.35f
    }
}
