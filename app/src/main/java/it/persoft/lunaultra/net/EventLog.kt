package it.persoft.lunaultra.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel { DEBUG, INFO, TX, RX, WARN, ERROR }

data class LogEntry(
    val timestampMs: Long,
    val level: LogLevel,
    val message: String,
    /** Righe aggiuntive: dump esadecimale e campi protobuf decodificati. */
    val detail: String? = null,
    /** Miniatura JPEG 256×256 dell'anteprima, incorporata nel log HTML condiviso. */
    val imageJpeg: ByteArray? = null,
) {
    val time: String get() = TIME_FORMAT.format(Date(timestampMs))

    /** Riga completa per l'esportazione: il dettaglio va indentato per restare leggibile. */
    fun toText(): String {
        val head = "$time ${level.name.padEnd(5)} $message"
        val body = buildList {
            detail?.takeIf { it.isNotBlank() }?.let { addAll(it.trimEnd().lines()) }
            if (imageJpeg != null) add("[miniatura 256×256 incorporata nel log HTML]")
        }
        if (body.isEmpty()) return head
        return head + "\n" + body.joinToString("\n") { "                  $it" }
    }

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY)
    }
}

/**
 * Log circolare condiviso: alimenta la schermata Diagnostica e l'esportazione.
 *
 * La capienza è ampia di proposito. Il log non serve a farsi un'idea mentre si guarda: serve a
 * essere esportato e letto dopo, per capire cosa la camera ha risposto davvero. Una sessione di
 * scansione produce centinaia di righe e troncarle vanificherebbe l'esportazione.
 */
class EventLog(private val capacity: Int = 5_000) {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun log(level: LogLevel, message: String, detail: String? = null, imageJpeg: ByteArray? = null) {
        // La copia rende l'entry immutabile anche se il buffer del JPEG viene riutilizzato.
        val entry = LogEntry(System.currentTimeMillis(), level, message, detail, imageJpeg?.copyOf())
        _entries.update { current ->
            val next = current + entry
            if (next.size > capacity) next.subList(next.size - capacity, next.size) else next
        }
    }

    fun debug(message: String, detail: String? = null, imageJpeg: ByteArray? = null) =
        log(LogLevel.DEBUG, message, detail, imageJpeg)
    fun info(message: String, detail: String? = null, imageJpeg: ByteArray? = null) =
        log(LogLevel.INFO, message, detail, imageJpeg)
    fun tx(message: String, detail: String? = null, imageJpeg: ByteArray? = null) =
        log(LogLevel.TX, message, detail, imageJpeg)
    fun rx(message: String, detail: String? = null, imageJpeg: ByteArray? = null) =
        log(LogLevel.RX, message, detail, imageJpeg)
    fun warn(message: String, detail: String? = null, imageJpeg: ByteArray? = null) =
        log(LogLevel.WARN, message, detail, imageJpeg)
    fun error(message: String, detail: String? = null, imageJpeg: ByteArray? = null) =
        log(LogLevel.ERROR, message, detail, imageJpeg)

    fun clear() = _entries.update { emptyList() }

    /** Testo completo, con dettagli: è questo che si condivide per farlo analizzare. */
    fun exportText(): String = _entries.value.joinToString("\n") { it.toText() }
}
