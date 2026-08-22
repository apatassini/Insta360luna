package it.persoft.lunaultra.media

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.LruCache
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.SocketBinder
import it.persoft.lunaultra.protocol.Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * La libreria della camera: elencare, guardare, scaricare.
 *
 * Due trasporti, ognuno per quello che sa fare. L'elenco passa dalla sessione di controllo,
 * perché dal firmware 1.0.238 la camera non pubblica più l'indice HTTP delle cartelle; i file
 * invece si scaricano in HTTP dal loro percorso, che resta valido finché la sessione è aperta.
 *
 * Tutto ciò che si scarica passa dalla rete Wi-Fi della camera, che non offre Internet: senza
 * legare esplicitamente le connessioni a quella rete, Android le manderebbe sui dati mobili e
 * non troverebbero nessuno.
 */
class MediaRepository(
    context: Context,
    private val commands: LunaCommands,
    private val settings: StateFlow<AppSettings>,
    private val binder: SocketBinder?,
    private val log: EventLog,
) {

    private val appContext = context.applicationContext
    private val thumbsDir = File(appContext.cacheDir, "thumbs")
    private val filesDir = File(appContext.cacheDir, "media")

    /**
     * Miniature già decodificate. Il tetto è in byte e non in numero di immagini: una griglia
     * lunga con miniature piccole sta in memoria, poche grandi no.
     */
    private val memory = object : LruCache<String, Bitmap>(THUMBNAIL_MEMORY_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Le ultime foto aperte, già decodificate.
     *
     * Il tetto è in byte perché una foto a schermo intero occupa quindici megabyte: contarle a
     * numero significherebbe tenerne dieci e finire la memoria alla quarta.
     */
    private val photos = object : LruCache<String, Bitmap>(photoBudgetBytes()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /**
     * Il comando della miniatura si spegne da solo dopo qualche rifiuto.
     *
     * Non tutte le camere lo implementano, e il modo in cui una camera dice «non lo conosco» è
     * far scadere l'attesa. Pagare quel timeout su ogni casella della griglia è il motivo per
     * cui le anteprime sembravano non arrivare mai.
     */
    @Volatile
    private var cameraThumbnailFailures = 0

    /** Le miniature fatte scaricando il file intero vanno una alla volta: pesano quanto il file. */
    private val heavyThumbnails = kotlinx.coroutines.sync.Semaphore(1)

    @Volatile
    private var announcedHeavyThumbnails = false

    @Volatile
    private var exifReports = 0

    /** OSC provato e non disponibile: si chiede una volta sola per sessione. */
    @Volatile
    private var oscUnavailable = false

    /**
     * I file di cui non si è riusciti a fare una miniatura.
     *
     * Senza questo elenco, ogni volta che la casella rientra nello schermo l'app riproverebbe a
     * scaricarne l'inizio: scorrere su e giù una griglia lunga diventerebbe un traffico continuo
     * verso la camera per immagini che non arriveranno comunque. Si svuota con «Aggiorna».
     */
    private val withoutThumbnail = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private val host: String get() = settings.value.host

    private fun photoBudgetBytes(): Int {
        val heap = Runtime.getRuntime().maxMemory()
        return (heap / 6).coerceIn(24L * 1024 * 1024, 96L * 1024 * 1024).toInt()
    }

    // ------------------------------------------------------------------ elenco

    /**
     * L'elenco completo dei media, a pagine.
     *
     * Il totale lo dichiara la camera; il tetto di sicurezza serve a non riempire la memoria
     * su una scheda con decine di migliaia di file — se ci si arriva, si vede comunque tutto
     * il più recente, che è quello che si cerca.
     */
    suspend fun list(): Result<List<MediaItem>> = withContext(Dispatchers.IO) {
        val paths = mutableListOf<String>()
        var start = 0
        var total = Int.MAX_VALUE
        while (start < total && paths.size < MAX_FILES) {
            val page = commands.getFileList(start, PAGE_SIZE)
                .getOrElse { return@withContext Result.failure(it) }
            page.total?.let { total = it }
            if (page.paths.isEmpty()) break
            paths += page.paths
            start += page.paths.size
        }
        log.info("Libreria: ${paths.size} file elencati dalla camera")
        Result.success(MediaIndex.fromPaths(paths))
    }

    // ------------------------------------------------------------------ miniature

    /**
     * La miniatura di un file, dalla via più economica disponibile.
     *
     * Nell'ordine: la cache, il comando della camera, l'anteprima EXIF dentro i primi byte del
     * file, il primo fotogramma del proxy video già scaricato. Se non c'è nessuna di queste,
     * restituisce null e la griglia mostra una casella con il tipo di file — che è meglio di
     * scaricare cinquanta megabyte per disegnare un quadratino.
     */
    suspend fun thumbnail(item: MediaItem): Bitmap? = withContext(Dispatchers.IO) {
        memory.get(item.path)?.let { return@withContext it }

        val cached = thumbFile(item)
        if (cached.exists()) {
            decodeFile(cached, THUMBNAIL_SIZE)?.let {
                memory.put(item.path, it)
                return@withContext it
            }
        }
        if (item.path in withoutThumbnail) return@withContext null

        // L'ordine conta più delle strategie stesse: prima quella che risponde in un secondo,
        // dopo quella che può far scadere un'attesa.
        val bytes = fromExifThumbnail(item)
            ?: fromCameraThumbnail(item)
            ?: fromVideoProxy(item)
            ?: fromFullFile(item)
        val bitmap = bytes?.let { decodeBytes(it, THUMBNAIL_SIZE) }
        if (bitmap == null) {
            withoutThumbnail.add(item.path)
            return@withContext null
        }
        saveThumbnail(item, bitmap)
        memory.put(item.path, bitmap)
        bitmap
    }

    private fun saveThumbnail(item: MediaItem, bitmap: Bitmap) {
        runCatching {
            thumbsDir.mkdirs()
            FileOutputStream(thumbFile(item)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
    }

    /**
     * Si fa dare dalla camera tutte le miniature in blocco, con l'API OSC.
     *
     * `camera.listFiles` restituisce, insieme all'elenco, una miniatura in base64 per ogni file:
     * una richiesta porta a casa quaranta anteprime già pronte, invece di quaranta scaricamenti
     * da venti megabyte l'uno. È la strada giusta quando c'è, e su una camera che non parla OSC
     * fallisce alla prima richiesta e non viene più tentata.
     *
     * Le voci si legano ai nostri file **per nome**: OSC riporta i percorsi come `/DCIM/...`
     * mentre l'elenco della sessione di controllo li dà come `/storage_internal/DCIM/...`, e
     * confrontare i percorsi non troverebbe niente.
     *
     * Restituisce quante miniature ha messo in cache.
     */
    suspend fun warmThumbnails(items: List<MediaItem>): Int = withContext(Dispatchers.IO) {
        if (oscUnavailable || items.isEmpty()) return@withContext 0
        val byName = items.associateBy { it.name }
        var stored = 0
        var start = 0
        while (start < items.size) {
            val page = OscMedia.listFiles(
                host = host,
                binder = binder,
                startPosition = start,
                entryCount = OSC_PAGE_SIZE,
                maxThumbSize = THUMBNAIL_SIZE,
                log = log,
            )
            if (page == null) {
                oscUnavailable = true
                return@withContext stored
            }
            if (page.isEmpty()) break
            for (entry in page) {
                val item = byName[entry.name] ?: continue
                val bytes = entry.thumbnail ?: continue
                val bitmap = decodeBytes(bytes, THUMBNAIL_SIZE) ?: continue
                saveThumbnail(item, bitmap)
                memory.put(item.path, bitmap)
                withoutThumbnail.remove(item.path)
                stored++
            }
            start += page.size
        }
        if (stored > 0) log.info("Miniature ricevute dalla camera con OSC: $stored")
        stored
    }

    private suspend fun fromCameraThumbnail(item: MediaItem): ByteArray? {
        if (cameraThumbnailFailures >= CAMERA_THUMBNAIL_GIVE_UP) return null
        val result = commands.getMiniThumbnail(item.path)
        result.onSuccess { cameraThumbnailFailures = 0 }
        result.onFailure {
            cameraThumbnailFailures++
            if (cameraThumbnailFailures == CAMERA_THUMBNAIL_GIVE_UP) {
                log.warn("La camera non manda miniature: uso solo l'anteprima dentro i file")
            }
        }
        return result.getOrNull()
    }

    /**
     * L'anteprima incorporata nei metadati EXIF, scaricando solo l'inizio del file.
     *
     * Una foto della Luna Ultra pesa decine di megabyte, ma la sua miniatura sta nei primi
     * chilobyte. Non si chiede al server un intervallo di byte — c'è chi risponde 416 e chi
     * ignora l'intestazione: si apre la richiesta normale, si leggono i primi mezzo megabyte e
     * si chiude. Funziona con qualunque server, e quello che non si legge non si scarica.
     */
    private fun fromExifThumbnail(item: MediaItem): ByteArray? {
        if (item.isVideo) return null
        val prefix = readPrefix(item.displayUrl(host), EXIF_PREFIX_BYTES) ?: return null
        val thumbnail = try {
            ExifInterface(java.io.ByteArrayInputStream(prefix)).thumbnailBytes
        } catch (e: Exception) {
            null
        }
        if (thumbnail == null && exifReports < DIAGNOSTIC_REPORTS) {
            exifReports++
            // Detto una volta sola e con i numeri in mano: senza, «non si vedono le anteprime»
            // resta indistinguibile da «la camera non risponde».
            log.warn(
                "Nessuna anteprima EXIF in ${item.name}: letti ${prefix.size} byte, " +
                    "inizio ${Hex.encode(prefix.copyOfRange(0, minOf(8, prefix.size)))}"
            )
        }
        return thumbnail
    }

    /**
     * L'ultima spiaggia: scaricare la foto intera e ridurla.
     *
     * Costa quanto la foto, e per questo viene per ultima e una alla volta. Ma è l'unica strada
     * che non dipende da cosa la camera ha deciso di mettere nel file: se l'immagine si scarica,
     * la miniatura si fa. Il risultato resta su disco, quindi il prezzo si paga una volta sola
     * per file, non a ogni scorrimento.
     */
    private suspend fun fromFullFile(item: MediaItem): ByteArray? {
        if (item.isVideo) return null
        if (!item.renderable && item.previewPath == null) return null
        return heavyThumbnails.withPermit {
            if (!announcedHeavyThumbnails) {
                announcedHeavyThumbnails = true
                log.info("Nessuna anteprima veloce: le miniature si fanno dalle foto intere, una alla volta")
            }
            val path = item.displayPath
            val cached = localFile(path)
            val existed = cached.exists() && cached.length() > 0
            val file = if (existed) cached else runCatching {
                downloadTo("http://$host$path", cached)
            }.getOrNull() ?: return@withPermit null

            val bitmap = decodeFile(file, THUMBNAIL_SIZE)
            // Se il file non era già in cache non lo si tiene: la cache serve a chi sfoglia,
            // non a chi ha solo guardato una griglia.
            if (!existed) runCatching { file.delete() }
            bitmap ?: return@withPermit null
            val stream = java.io.ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            // Si scrive subito su disco: se la casella è già uscita dallo schermo e la coroutine
            // viene annullata, il file intero è stato scaricato per niente.
            saveThumbnail(item, bitmap)
            stream.toByteArray()
        }
    }

    /**
     * Il primo fotogramma del proxy del video.
     *
     * Il proxy è la versione a bassa risoluzione che la camera salva accanto a ogni ripresa:
     * scaricarlo per intero costa pochi megabyte, e serve comunque per guardare il video.
     * Del file grosso invece non si scarica niente.
     */
    private fun fromVideoProxy(item: MediaItem): ByteArray? {
        if (!item.isVideo) return null
        val proxyPath = item.proxyPath ?: return null
        val local = localFile(proxyPath)
        if (!local.exists() || local.length() == 0L) {
            val downloaded = runCatching { downloadTo(("http://" + host + proxyPath), local) }
            if (downloaded.isFailure) return null
        }
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(local.absolutePath)
            val frame = retriever.getFrameAtTime(0) ?: return null
            val stream = java.io.ByteArrayOutputStream()
            frame.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            stream.toByteArray()
        } catch (e: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** I primi [maxBytes] di una risorsa, poi si chiude senza aspettare il resto. */
    private fun readPrefix(url: String, maxBytes: Int): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = open(url)
            val buffer = ByteArray(32 * 1024)
            val out = java.io.ByteArrayOutputStream(maxBytes.coerceAtMost(64 * 1024))
            connection.inputStream.use { input ->
                while (out.size() < maxBytes) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.toByteArray().takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            log.warn("Lettura dell'inizio di $url non riuscita: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    // ------------------------------------------------------------------ visione

    /**
     * Porta un file in cache locale e restituisce il file.
     *
     * Guardare un video in streaming dalla camera è fragile: il lettore di sistema apre le sue
     * connessioni, che non passano dal binding sulla rete della camera. Scaricare prima e
     * riprodurre dal file locale è più lento a iniziare e infinitamente più affidabile.
     */
    suspend fun cache(
        item: MediaItem,
        preferProxy: Boolean = false,
        onProgress: (Float) -> Unit = {},
    ): Result<File> = withContext(Dispatchers.IO) {
        val path = if (preferProxy) item.proxyPath ?: item.displayPath else item.displayPath
        val target = localFile(path)
        if (target.exists() && target.length() > 0) {
            // Rimetterlo in cima alla fila: è appena stato usato, non è il candidato da buttare.
            target.setLastModified(System.currentTimeMillis())
            onProgress(1f)
            return@withContext Result.success(target)
        }
        runCatching { downloadTo("http://$host$path", target, onProgress) }
            .onSuccess { pruneDownloads() }
            .onFailure { log.warn("Scaricamento di $path non riuscito: ${it.message}") }
    }

    /** Scarica una risorsa in un file locale, passando da un file parziale. */
    private fun downloadTo(url: String, target: File, onProgress: (Float) -> Unit = {}): File {
        filesDir.mkdirs()
        val partial = File(target.absolutePath + ".part")
        var connection: HttpURLConnection? = null
        try {
            connection = open(url)
            val length = connection.contentLengthLong
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output -> copy(input, output, length, onProgress) }
            }
        } finally {
            connection?.disconnect()
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        return target
    }

    /**
     * Una foto pronta da guardare: dalla memoria se c'è, altrimenti scaricata e decodificata
     * alla dimensione che serve allo schermo, non a quella originale.
     */
    suspend fun loadPhoto(
        item: MediaItem,
        maxSize: Int,
        onProgress: (Float) -> Unit = {},
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        photos.get(item.path)?.let {
            onProgress(1f)
            return@withContext Result.success(it)
        }
        cache(item, preferProxy = false, onProgress = onProgress).mapCatching { file ->
            val bitmap = decodeFile(file, maxSize) ?: error("formato non visualizzabile")
            photos.put(item.path, bitmap)
            bitmap
        }
    }

    /**
     * Porta avanti il lavoro sul file successivo mentre l'utente guarda quello aperto.
     *
     * Sfogliare una galleria è un gesto veloce e prevedibile: la foto dopo la si guarda quasi
     * sempre, e scaricarla mentre l'occhio è ancora sulla precedente è tempo che non si aspetta.
     */
    suspend fun prefetch(item: MediaItem, maxSize: Int) = withContext(Dispatchers.IO) {
        if (item.isVideo) {
            if (item.proxyPath != null) cache(item, preferProxy = true)
            return@withContext
        }
        if (photos.get(item.path) != null) return@withContext
        if (!item.renderable && item.previewPath == null) return@withContext
        cache(item, preferProxy = false).onSuccess { file ->
            decodeFile(file, maxSize)?.let { photos.put(item.path, it) }
        }
    }

    // ------------------------------------------------------------------ scaricamento

    /**
     * Salva il file nella galleria del telefono.
     *
     * Il flusso va diritto dentro MediaStore invece di passare dalla cache: un video lungo
     * occuperebbe due volte lo spazio, e su un telefono pieno è la differenza fra riuscire e no.
     */
    suspend fun saveToGallery(
        item: MediaItem,
        onProgress: (Float) -> Unit = {},
    ): Result<Uri> = withContext(Dispatchers.IO) {
        runCatching {
            var connection: HttpURLConnection? = null
            try {
                connection = open(item.url(host))
                val length = connection.contentLengthLong
                val stream = connection.inputStream
                val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    writeToMediaStore(item, length) { output -> copy(stream, output, length, onProgress) }
                } else {
                    writeToPublicDirectory(item) { output -> copy(stream, output, length, onProgress) }
                }
                log.info("Salvato ${item.name} nella galleria del telefono")
                uri
            } finally {
                connection?.disconnect()
            }
        }.onFailure { log.warn("Salvataggio di ${item.name} non riuscito: ${it.message}") }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun writeToMediaStore(item: MediaItem, length: Long, write: (OutputStream) -> Unit): Uri {
        val collection = if (item.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, item.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType(item.extension))
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath(item))
            if (length > 0) put(MediaStore.MediaColumns.SIZE, length)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = appContext.contentResolver
        val uri = resolver.insert(collection, values) ?: error("la galleria ha rifiutato il file")
        try {
            resolver.openOutputStream(uri)?.use(write) ?: error("nessuno spazio dove scrivere")
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return uri
    }

    /** Prima di Android 10 la galleria è una cartella, e va segnalata allo scanner dei media. */
    private fun writeToPublicDirectory(item: MediaItem, write: (OutputStream) -> Unit): Uri {
        val root = Environment.getExternalStoragePublicDirectory(
            if (item.isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        )
        val directory = File(root, GALLERY_FOLDER).apply { mkdirs() }
        val target = File(directory, item.name)
        FileOutputStream(target).use(write)
        MediaScannerConnection.scanFile(appContext, arrayOf(target.absolutePath), arrayOf(mimeType(item.extension)), null)
        return Uri.fromFile(target)
    }

    private fun relativePath(item: MediaItem): String =
        if (item.isVideo) "${Environment.DIRECTORY_MOVIES}/$GALLERY_FOLDER"
        else "${Environment.DIRECTORY_PICTURES}/$GALLERY_FOLDER"

    // ------------------------------------------------------------------ utilità

    fun localFile(path: String): File = File(filesDir, path.replace('/', '_'))

    private fun thumbFile(item: MediaItem): File = File(thumbsDir, item.path.replace('/', '_') + ".jpg")

    /** Nuovo tentativo per le miniature che erano fallite: lo chiede chi preme «Aggiorna». */
    fun retryThumbnails() {
        withoutThumbnail.clear()
        cameraThumbnailFailures = 0
    }

    /** Svuota le copie locali. Le miniature restano: costano poco e si rifarebbero subito. */
    fun clearDownloads() {
        runCatching { filesDir.deleteRecursively() }
        photos.evictAll()
    }

    /**
     * Tiene in cache solo gli ultimi file aperti.
     *
     * Senza un tetto, sfogliare una galleria da ottanta scatti riempirebbe la memoria del
     * telefono di copie che non si riguarderanno: si tengono i più recenti, che sono quelli
     * su cui si sta tornando avanti e indietro.
     */
    private fun pruneDownloads(keep: Int = CACHE_KEEP_FILES) {
        val files = filesDir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") } ?: return
        if (files.size <= keep) return
        files.sortedByDescending { it.lastModified() }
            .drop(keep)
            .forEach { runCatching { it.delete() } }
    }

    private fun open(url: String): HttpURLConnection {
        val target = URL(url)
        val connection = (binder?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        connection.connect()
        return connection
    }

    private fun copy(input: InputStream, output: OutputStream, total: Long, onProgress: (Float) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var written = 0L
        var lastReported = 0f
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            output.write(buffer, 0, read)
            written += read
            if (total > 0) {
                val progress = (written.toFloat() / total).coerceIn(0f, 1f)
                // Un aggiornamento ogni punto percentuale: la barra si muove, la UI non annega.
                if (progress - lastReported >= 0.01f) {
                    lastReported = progress
                    onProgress(progress)
                }
            }
        }
        output.flush()
        onProgress(1f)
    }

    /**
     * Decodifica ridotta. Il `runCatching` prende anche l'esaurimento della memoria, che qui non
     * è un caso raro: una foto da duecento megapixel non entra nell'heap a dimensione piena, e
     * far morire l'app mentre si guarda una galleria è il modo peggiore di dirlo.
     */
    private fun decodeFile(file: File, maxSize: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrElse {
        log.warn("Decodifica di ${file.name} non riuscita: ${it.message}")
        null
    }

    private fun decodeBytes(bytes: ByteArray, maxSize: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }.getOrNull()

    /**
     * Il fattore di riduzione in decodifica. Una foto della Luna Ultra non entra in memoria a
     * dimensione piena, e su uno schermo da telefono non servirebbe comunque.
     */
    private fun sampleSize(width: Int, height: Int, maxSize: Int): Int {
        if (width <= 0 || height <= 0 || maxSize <= 0) return 1
        var sample = 1
        while (width / (sample * 2) >= maxSize || height / (sample * 2) >= maxSize) sample *= 2
        return sample
    }

    private fun mimeType(extension: String): String = when (extension.lowercase()) {
        "mp4", "lrv" -> "video/mp4"
        "mov" -> "video/quicktime"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "dng" -> "image/x-adobe-dng"
        else -> "image/jpeg"
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_FILES = 5_000
        const val THUMBNAIL_SIZE = 320
        const val THUMBNAIL_MEMORY_BYTES = 16 * 1024 * 1024
        /** Quanto si legge dall'inizio di una foto per trovarci dentro l'anteprima EXIF. */
        const val EXIF_PREFIX_BYTES = 2 * 1024 * 1024

        /** Quante volte spiegare nel log perché una strategia non ha prodotto miniature. */
        const val DIAGNOSTIC_REPORTS = 2

        /** Dopo tanti rifiuti di fila, il comando della miniatura si considera non supportato. */
        const val CAMERA_THUMBNAIL_GIVE_UP = 3

        /** Quanti file scaricati tenere in cache locale. */
        const val CACHE_KEEP_FILES = 10

        /** Quante voci per richiesta OSC: ognuna porta con sé la sua miniatura in base64. */
        const val OSC_PAGE_SIZE = 40
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        const val GALLERY_FOLDER = "Luna Ultra"
    }
}
