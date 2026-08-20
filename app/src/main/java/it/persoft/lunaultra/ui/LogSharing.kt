package it.persoft.lunaultra.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Esporta il log su file e apre il pannello di condivisione.
 *
 * Il log è il modo per far analizzare a distanza cosa risponde la camera, quindi conta che
 * arrivi intero: viene scritto in un file e condiviso come allegato, non incollato dentro
 * l'intent, che oltre poche centinaia di kB verrebbe rifiutato dal sistema.
 */
object LogSharing {

    private const val AUTHORITY_SUFFIX = ".fileprovider"

    fun share(context: Context, content: String, headerLines: List<String> = emptyList()): Result<Unit> =
        runCatching {
            val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ITALY).format(Date())
            val directory = File(context.cacheDir, "log").apply { mkdirs() }
            val file = File(directory, "luna-log-$stamp.txt")

            val header = buildString {
                appendLine("# Luna Ultra Timelapse Controller — log di sessione")
                appendLine("# $stamp")
                headerLines.forEach { appendLine("# $it") }
                appendLine()
            }
            file.writeText(header + content)

            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + AUTHORITY_SUFFIX,
                file,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
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
}
