package it.persoft.lunaultra.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * La calibrazione su file, per non doverla rifare.
 *
 * Misurarla costa sette minuti di gimbal che va a sbattere nei fine corsa, e il risultato vive
 * in un file dentro l'app: disinstallando, sparisce. Ma è una misura dell'*hardware*, non una
 * preferenza — la corsa degli assi e la curva dei comandi sono le stesse ieri e domani — quindi
 * l'unica ragione per rifarla è averla persa. Da qui si porta fuori e si rimette dentro.
 *
 * Il formato è lo stesso JSON che l'app salva per sé: quello che esce è quello che c'era.
 */
object CalibrationBackup {

    private const val AUTHORITY_SUFFIX = ".fileprovider"
    private const val MIME = "application/json"

    fun fileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(Date())
        return "luna-calibrazione-$stamp.json"
    }

    /** Nei Download, dove un file si ritrova anche fra un mese e due telefoni. */
    fun saveToDownloads(context: Context, json: String): Result<String> = runCatching {
        val name = fileName()
        val bytes = json.toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, MIME)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Android non ha creato il file in Download")
            try {
                resolver.openOutputStream(uri, "w")?.use { it.write(bytes) }
                    ?: error("Impossibile aprire il file in Download")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (error: Throwable) {
                resolver.delete(uri, null, null)
                throw error
            }
        } else {
            @Suppress("DEPRECATION")
            val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                .apply { mkdirs() }
            File(directory, name).writeBytes(bytes)
        }
        "Download/$name"
    }

    /**
     * La stessa copia, mandata dove si vuole.
     *
     * I Download stanno sul telefono, e un telefono si perde insieme alla calibrazione che
     * contiene. Mandarsela per posta o metterla nel cloud è l'unico modo perché sopravviva al
     * telefono, ed è un tocco.
     */
    fun share(context: Context, json: String): Result<Unit> = runCatching {
        val directory = File(context.cacheDir, "backup").apply { mkdirs() }
        val file = File(directory, fileName())
        file.writeText(json)
        val uri = FileProvider.getUriForFile(context, context.packageName + AUTHORITY_SUFFIX, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = MIME
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Calibrazione gimbal Luna Ultra")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(
            Intent.createChooser(intent, "Salva la calibrazione").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }

    /** Rilegge il file scelto dal selettore di sistema. */
    fun read(context: Context, uri: Uri): Result<String> = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            input.readBytes().toString(Charsets.UTF_8)
        } ?: error("Il file scelto non si apre")
    }
}
