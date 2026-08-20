package it.persoft.lunaultra.preview

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import it.persoft.lunaultra.net.SocketBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Anteprima MJPEG via l'endpoint OSC della camera.
 *
 * È la strada più semplice quando la camera la offre: una POST a
 * `/osc/commands/execute` con `camera.getLivePreview` restituisce un multipart di JPEG, che
 * basta ritagliare e decodificare. Nessun decoder video, nessuna superficie da gestire.
 *
 * Il parsing non si fida degli header `Content-Length` del multipart — alcuni firmware li
 * omettono — e cerca invece i marcatori JPEG `FFD8` … `FFD9` nel flusso: funziona in entrambi
 * i casi.
 */
object MjpegStream {

    private const val PATH = "/osc/commands/execute"
    private const val REQUEST = """{"name":"camera.getLivePreview"}"""
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 8_000

    /** Oltre questa soglia il flusso non è più un JPEG plausibile: si riparte. */
    private const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    private const val SOI_1 = 0xFF
    private const val SOI_2 = 0xD8
    private const val EOI_2 = 0xD9

    /**
     * Verifica se la camera offre l'anteprima MJPEG, senza consumarla.
     * Restituisce true solo se risponde con un tipo di contenuto compatibile.
     */
    suspend fun isAvailable(host: String, binder: SocketBinder?): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = open(host, binder)
            val type = connection.contentType.orEmpty().lowercase()
            connection.responseCode == HttpURLConnection.HTTP_OK &&
                (type.contains("multipart") || type.contains("jpeg"))
        } catch (_: Exception) {
            false
        } finally {
            connection?.disconnect()
        }
    }

    /** Flusso di fotogrammi decodificati. Si chiude quando il collector viene cancellato. */
    fun frames(host: String, binder: SocketBinder?): Flow<Bitmap> = flow {
        var connection: HttpURLConnection? = null
        try {
            connection = open(host, binder)
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                error("L'anteprima OSC ha risposto ${connection.responseCode}")
            }
            val input = BufferedInputStream(connection.inputStream, 64 * 1024)
            while (currentCoroutineContext().isActive) {
                val jpeg = readNextJpeg(input) ?: break
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: continue
                emit(bitmap)
            }
        } finally {
            connection?.disconnect()
        }
    }.flowOn(Dispatchers.IO)

    private fun open(host: String, binder: SocketBinder?): HttpURLConnection {
        // Il socket va legato alla rete della camera: quel Wi-Fi non ha Internet e Android,
        // lasciato a sé, instraderebbe la richiesta sul traffico dati.
        val url = URL("http://${host.trim().removeSuffix("/")}$PATH")
        val connection = (binder?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json;charset=utf-8")
        connection.outputStream.use { it.write(REQUEST.toByteArray(Charsets.UTF_8)) }
        return connection
    }

    /**
     * Estrae il prossimo JPEG completo dal flusso, saltando gli header del multipart.
     * Restituisce null a fine flusso.
     */
    private fun readNextJpeg(input: InputStream): ByteArray? {
        // Cerca l'inizio immagine FF D8.
        var previous = input.read()
        if (previous < 0) return null
        while (true) {
            val current = input.read()
            if (current < 0) return null
            if (previous == SOI_1 && current == SOI_2) break
            previous = current
        }

        val out = java.io.ByteArrayOutputStream(64 * 1024)
        out.write(SOI_1)
        out.write(SOI_2)

        // Copia fino a fine immagine FF D9.
        var last = 0
        while (out.size() < MAX_FRAME_BYTES) {
            val current = input.read()
            if (current < 0) return null
            out.write(current)
            if (last == SOI_1 && current == EOI_2) return out.toByteArray()
            last = current
        }
        // Frame implausibile: lo si scarta e il chiamante riprova dal successivo.
        return null
    }
}
