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
) {
    val time: String get() = TIME_FORMAT.format(Date(timestampMs))

    private companion object {
        val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.ITALY)
    }
}

/** Log circolare condiviso: alimenta la schermata Diagnostica e l'export testuale. */
class EventLog(private val capacity: Int = 500) {

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries

    fun log(level: LogLevel, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        _entries.update { current ->
            val next = current + entry
            if (next.size > capacity) next.subList(next.size - capacity, next.size) else next
        }
    }

    fun debug(message: String) = log(LogLevel.DEBUG, message)
    fun info(message: String) = log(LogLevel.INFO, message)
    fun tx(message: String) = log(LogLevel.TX, message)
    fun rx(message: String) = log(LogLevel.RX, message)
    fun warn(message: String) = log(LogLevel.WARN, message)
    fun error(message: String) = log(LogLevel.ERROR, message)

    fun clear() = _entries.update { emptyList() }

    fun exportText(): String = _entries.value.joinToString("\n") { "${it.time} ${it.level} ${it.message}" }
}
