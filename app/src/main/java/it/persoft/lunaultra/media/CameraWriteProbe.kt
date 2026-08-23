package it.persoft.lunaultra.media

import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.SocketBinder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Un tentativo di scrittura sulla camera, con quello che la camera ha risposto.
 *
 * [status] è il codice HTTP, oppure zero quando la connessione non è nemmeno arrivata a
 * risposta. [allow] è l'intestazione `Allow`, che su OPTIONS è la risposta più diretta alla
 * domanda: dice quali metodi il server accetta senza che si debba provarli uno per uno.
 */
data class WriteProbeStep(
    val method: String,
    val path: String,
    val status: Int,
    val message: String,
    val allow: String? = null,
) {
    /** Vero solo per un codice che significa «l'ho fatto», non «l'ho capito». */
    val succeeded: Boolean get() = status in 200..204 || status == 201

    val summary: String
        get() = buildString {
            append("$method $path → ")
            append(if (status > 0) "$status $message" else "nessuna risposta: $message")
            allow?.let { append(" · Allow: $it") }
        }
}

data class WriteProbeResult(
    val steps: List<WriteProbeStep>,
    val writablePath: String?,
) {
    val canWrite: Boolean get() = writablePath != null
}

/**
 * Verifica se si può scrivere sulla scheda della camera, invece di supporre.
 *
 * I file si scaricano in HTTP dal loro percorso, quindi sulla camera gira un server HTTP: la
 * domanda è se quel server accetta anche di ricevere. Non si può dedurre — un server che serve
 * file è quasi sempre in sola lettura, ma «quasi sempre» non è una risposta — quindi si prova.
 *
 * Si comincia da OPTIONS, che è la domanda diretta: un server che accetta PUT lo dichiara
 * nell'intestazione `Allow`. Poi si tenta davvero, perché molti server non implementano OPTIONS
 * e rispondono male a una domanda a cui saprebbero rispondere bene. Ogni tentativo scrive un
 * file minuscolo con un nome riconoscibile, così se qualcosa passa si sa cosa cancellare.
 *
 * Un 405 o un 403 sono risposte utili quanto un 200: dicono che il server ha capito e ha detto
 * di no, e chiudono la questione. Il caso da distinguere è il 404, che può voler dire «quel
 * percorso non esiste» invece di «non si scrive»: per questo si prova su più cartelle.
 */
class CameraWriteProbe(
    private val binder: SocketBinder?,
    private val log: EventLog,
) {

    suspend fun probe(host: String): WriteProbeResult = withContext(Dispatchers.IO) {
        val steps = mutableListOf<WriteProbeStep>()
        var writable: String? = null

        PROBE_DIRECTORIES.forEach { directory ->
            steps += request(host, "OPTIONS", directory, body = null)
        }

        PROBE_DIRECTORIES.forEach { directory ->
            if (writable != null) return@forEach
            val path = directory.trimEnd('/') + "/" + PROBE_FILE_NAME
            val put = request(host, "PUT", path, body = PROBE_BODY)
            steps += put
            if (put.succeeded) {
                writable = path
                return@forEach
            }
            val post = request(host, "POST", path, body = PROBE_BODY)
            steps += post
            if (post.succeeded) writable = path
        }

        // L'interfaccia OSC è l'altra porta di servizio della camera. Non ha un comando di
        // caricamento nello standard, ma dice quali comandi *questa* camera espone: se ce n'è
        // uno per scrivere, è elencato lì e si vede nella risposta.
        steps += request(host, "GET", "/osc/info", body = null)

        val result = WriteProbeResult(steps, writable)
        log.info(
            "SCRITTURA SULLA CAMERA · PROVA",
            buildString {
                steps.forEach { appendLine(it.summary) }
                append(
                    if (result.canWrite) {
                        "Scrittura riuscita su ${result.writablePath}: cancella quel file dalla " +
                            "galleria quando hai finito di provare."
                    } else {
                        "Nessun metodo di scrittura accettato: il server della camera serve i " +
                            "file e basta. Le panoramiche unite restano sul telefono."
                    },
                )
            },
        )
        result
    }

    private fun request(host: String, method: String, path: String, body: ByteArray?): WriteProbeStep {
        val url = URL("http://$host$path")
        var connection: HttpURLConnection? = null
        return try {
            connection = (binder?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            connection.requestMethod = method
            connection.connectTimeout = PROBE_TIMEOUT_MS
            connection.readTimeout = PROBE_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            WriteProbeStep(
                method = method,
                path = path,
                status = connection.responseCode,
                message = connection.responseMessage ?: "",
                allow = connection.getHeaderField("Allow"),
            )
        } catch (error: IOException) {
            // Un server che rifiuta il metodo può chiudere la connessione invece di rispondere:
            // è comunque un no, e va registrato come tale invece di far fallire la prova.
            WriteProbeStep(method, path, status = 0, message = error.message ?: error.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        /** Le cartelle dove ha senso provare: la radice, i media, e la cartella degli scatti. */
        val PROBE_DIRECTORIES = listOf("/", "/DCIM/", "/DCIM/Camera01/")

        /** Nome riconoscibile: se qualcosa passa, si sa che è nostro e si può cancellare. */
        const val PROBE_FILE_NAME = "luna_app_prova_scrittura.txt"
        val PROBE_BODY = "prova di scrittura dell'app Luna".toByteArray()
        const val PROBE_TIMEOUT_MS = 4_000
    }
}
