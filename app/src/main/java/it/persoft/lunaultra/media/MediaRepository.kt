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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
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

    private val host: String get() = settings.value.host

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

        val bytes = fromCameraThumbnail(item) ?: fromExifThumbnail(item) ?: fromCachedVideoFrame(item)
        val bitmap = bytes?.let { decodeBytes(it, THUMBNAIL_SIZE) } ?: return@withContext null
        runCatching {
            thumbsDir.mkdirs()
            FileOutputStream(cached).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        }
        memory.put(item.path, bitmap)
        bitmap
    }

    private suspend fun fromCameraThumbnail(item: MediaItem): ByteArray? {
        commands.getMiniThumbnail(item.path).getOrNull()?.let { return it }
        if (item.displayPath != item.path) {
            commands.getMiniThumbnail(item.displayPath).getOrNull()?.let { return it }
        }
        return null
    }

    /**
     * L'anteprima incorporata nei metadati EXIF, scaricando solo l'inizio del file.
     *
     * Un JPEG da 200 megapixel pesa decine di megabyte, ma la sua miniatura sta nei primi
     * chilobyte: una richiesta con `Range` la porta a casa senza scaricare la foto.
     */
    private fun fromExifThumbnail(item: MediaItem): ByteArray? {
        if (item.isVideo) return null
        if (item.extension !in MediaItem.RENDERABLE_IMAGE_EXTENSIONS && item.extension != "dng") return null
        var connection: HttpURLConnection? = null
        return try {
            connection = open(item.displayUrl(host), rangeBytes = EXIF_RANGE_BYTES)
            connection.inputStream.use { stream ->
                ExifInterface(stream).thumbnailBytes
            }
        } catch (e: Exception) {
            log.warn("Miniatura EXIF non disponibile per ${item.name}: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    /** Se il proxy del video è già in cache, il primo fotogramma è gratis. */
    private fun fromCachedVideoFrame(item: MediaItem): ByteArray? {
        if (!item.isVideo) return null
        val local = localFile(item.proxyPath ?: return null).takeIf { it.exists() } ?: return null
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
            onProgress(1f)
            return@withContext Result.success(target)
        }
        runCatching {
            filesDir.mkdirs()
            val partial = File(target.absolutePath + ".part")
            var connection: HttpURLConnection? = null
            try {
                connection = open("http://$host$path")
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
            target
        }.onFailure { log.warn("Scaricamento di $path non riuscito: ${it.message}") }
    }

    /** Una foto decodificata alla dimensione che serve per guardarla, non a quella originale. */
    suspend fun loadPhoto(
        item: MediaItem,
        maxSize: Int,
        onProgress: (Float) -> Unit = {},
    ): Result<Bitmap> = withContext(Dispatchers.IO) {
        cache(item, preferProxy = false, onProgress = onProgress).mapCatching { file ->
            decodeFile(file, maxSize) ?: error("formato non visualizzabile")
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

    /** Svuota le copie locali. Le miniature restano: costano poco e si rifarebbero subito. */
    fun clearDownloads() {
        runCatching { filesDir.deleteRecursively() }
    }

    private fun open(url: String, rangeBytes: Long? = null): HttpURLConnection {
        val target = URL(url)
        val connection = (binder?.openConnection(target) ?: target.openConnection()) as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = true
        if (rangeBytes != null) connection.setRequestProperty("Range", "bytes=0-${rangeBytes - 1}")
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

    private fun decodeFile(file: File, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        }
        return BitmapFactory.decodeFile(file.absolutePath, options)
    }

    private fun decodeBytes(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, maxSize)
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

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
        const val THUMBNAIL_MEMORY_BYTES = 24 * 1024 * 1024
        const val EXIF_RANGE_BYTES = 256L * 1024L
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 20_000
        const val GALLERY_FOLDER = "Luna Ultra"
    }
}
