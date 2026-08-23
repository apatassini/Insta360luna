package it.persoft.lunaultra.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.core.content.FileProvider
import it.persoft.lunaultra.net.LogEntry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Esporta il log su file e apre il pannello di condivisione.
 *
 * Il log è il modo per analizzare a distanza cosa risponde e dove guarda la camera. Viene
 * scritto in un HTML autosufficiente con le miniature incorporate, poi condiviso come allegato:
 * testo e immagini non possono separarsi durante l'invio.
 */
object LogSharing {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun share(context: Context, entries: List<LogEntry>, headerLines: List<String> = emptyList()): Result<Unit> =
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(Date())
            val directory = File(context.cacheDir, "log").apply { mkdirs() }
            val file = File(directory, "luna-log-$stamp.html")

            file.writeText(renderHtml(stamp, entries, headerLines))

            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/html"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "Log Luna Ultra $stamp")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, "Condividi il log").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }

    /** Salva lo stesso HTML autosufficiente nella cartella Download del telefono. */
    fun saveToDownloads(
        context: Context,
        entries: List<LogEntry>,
        headerLines: List<String> = emptyList(),
    ): Result<String> = runCatching {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(Date())
        val fileName = "luna-log-$stamp.html"
        val bytes = renderHtml(stamp, entries, headerLines).toByteArray(Charsets.UTF_8)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/html")
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
            File(directory, fileName).writeBytes(bytes)
        }
        "Download/$fileName"
    }

    /**
     * Un solo HTML autosufficiente: le miniature sono data URI, quindi non possono separarsi
     * dal log quando viene condiviso o allegato per l'analisi.
     */
    private fun renderHtml(stamp: String, entries: List<LogEntry>, headerLines: List<String>): String =
        buildString {
            appendLine("<!doctype html><html lang=\"it\"><head><meta charset=\"utf-8\">")
            appendLine("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            appendLine("<title>Log Luna Ultra $stamp</title><style>")
            appendLine("body{background:#0b0d12;color:#e8eaf0;font-family:monospace;margin:16px}")
            appendLine("h1{font:600 20px sans-serif}.meta{color:#aeb5c5}.e{padding:7px 0;border-bottom:1px solid #252a35}")
            appendLine(".DEBUG,.INFO{color:#e8eaf0}.TX{color:#d6a8ff}.RX{color:#69c9ff}.WARN,.ERROR{color:#ff8d8d}")
            appendLine("pre{white-space:pre-wrap;margin:5px 0 3px 18px;color:#bdc3d1}.thumb{display:block;width:256px;height:256px;object-fit:contain;background:#000;margin:8px 0 8px 18px;border:1px solid #3b4252}")
            appendLine("</style></head><body><h1>Luna Ultra — log diagnostico</h1>")
            appendLine("<div class=\"meta\">$stamp<br>")
            headerLines.forEach { append(escapeHtml(it)).append("<br>") }
            appendLine("</div>")
            entries.forEach { entry ->
                append("<div class=\"e ").append(entry.level.name).append("\"><b>")
                append(escapeHtml(entry.time)).append(" &nbsp; ").append(entry.level.name).append(" &nbsp; ")
                append(escapeHtml(entry.message)).appendLine("</b>")
                entry.detail?.takeIf(String::isNotBlank)?.let { detail ->
                    append("<pre>").append(escapeHtml(detail)).appendLine("</pre>")
                }
                entry.imageJpeg?.let { jpeg ->
                    val encoded = Base64.encodeToString(jpeg, Base64.NO_WRAP)
                    append("<img class=\"thumb\" alt=\"Miniatura diagnostica\" src=\"data:image/jpeg;base64,")
                    append(encoded).appendLine("\">")
                }
                appendLine("</div>")
            }
            appendLine("</body></html>")
        }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            append(
                when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    else -> char
                },
            )
        }
    }
}
