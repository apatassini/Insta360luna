package it.persoft.lunaultra.media

import android.util.Base64
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.SocketBinder
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * L'API OSC della camera, usata per una cosa sola: farsi dare le miniature già pronte.
 *
 * OSC (Open Spherical Camera) è lo standard che Insta360 implementa sulle sue camere a 360°, e
 * `camera.listFiles` sa restituire, insieme all'elenco, una miniatura in base64 per ogni file.
 * È la differenza fra scaricare venti megabyte per disegnare un quadratino e riceverne venti
 * già disegnati in una richiesta sola.
 *
 * Non tutte le camere lo offrono, e la Luna Ultra non è nell'elenco ufficiale: per questo il
 * chiamante prova una volta e, se non risponde, non ci torna più.
 */
object OscMedia {

    private const val PATH = "/osc/commands/execute"
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 15_000

    /** Una voce dell'elenco OSC. Il percorso locale è quello che lega la voce al nostro file. */
    data class Entry(
        val name: String,
        val localPath: String?,
        val sizeBytes: Long,
        val thumbnail: ByteArray?,
    )

    /**
     * Una pagina di `camera.listFiles`. Restituisce null quando la camera non parla OSC —
     * che è diverso da una pagina vuota, e il chiamante deve poterli distinguere.
     */
    fun listFiles(
        host: String,
        binder: SocketBinder?,
        startPosition: Int,
        entryCount: Int,
        maxThumbSize: Int,
        log: EventLog,
    ): List<Entry>? {
        val request = JSONObject()
            .put("name", "camera.listFiles")
            .put(
                "parameters",
                JSONObject()
                    .put("fileType", "all")
                    .put("startPosition", startPosition)
                    .put("entryCount", entryCount)
                    .put("maxThumbSize", maxThumbSize),
            )
            .toString()

        var connection: HttpURLConnection? = null
        return try {
            val url = URL("http://$host$PATH")
            connection = (binder?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Content-Type", "application/json;charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            // Richiesta dallo standard OSC: senza, alcune camere rifiutano il comando.
            connection.setRequestProperty("X-XSRF-Protected", "1")
            connection.outputStream.use { it.write(request.toByteArray(Charsets.UTF_8)) }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                log.warn("OSC listFiles: la camera ha risposto ${connection.responseCode}")
                return null
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            parse(body)
        } catch (e: Exception) {
            log.warn("OSC non disponibile su questa camera: ${e.message}")
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun parse(body: String): List<Entry>? {
        val root = JSONObject(body)
        if (root.optString("state") == "error") return null
        val entries = root.optJSONObject("results")?.optJSONArray("entries") ?: return null
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val name = entry.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val encoded = entry.optString("thumbnail").takeIf { it.isNotBlank() }
            Entry(
                name = name,
                localPath = entry.optString("_localFileUrl").takeIf { it.isNotBlank() },
                sizeBytes = entry.optLong("size", 0L),
                thumbnail = encoded?.let { runCatching { Base64.decode(it, Base64.DEFAULT) }.getOrNull() },
            )
        }
    }
}
