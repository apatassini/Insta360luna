package it.persoft.lunaultra.ui

import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.timelapse.RunPhase

fun ConnectionState.italianLabel(): String = when (this) {
    ConnectionState.DISCONNECTED -> "Disconnesso"
    ConnectionState.CONNECTING -> "Connessione…"
    ConnectionState.HANDSHAKE -> "Handshake…"
    ConnectionState.CONNECTED -> "Connesso"
    ConnectionState.ERROR -> "Errore"
}

fun RunPhase.italianLabel(): String = when (this) {
    RunPhase.IDLE -> "In attesa"
    RunPhase.PREPARING -> "Preparazione"
    RunPhase.RUNNING -> "In esecuzione"
    RunPhase.STOPPING -> "Arresto"
    RunPhase.COMPLETED -> "Completata"
    RunPhase.ABORTED -> "Interrotta"
}

/** Durata in mm:ss, il formato del cronometro di ripresa. */
fun formatClock(totalSeconds: Int): String {
    val safe = totalSeconds.coerceAtLeast(0)
    return "%02d:%02d".format(safe / 60, safe % 60)
}

/** Spazio su scheda in unità leggibili: i byte grezzi non dicono niente a nessuno. */
fun formatBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    return if (gb >= 1.0) "%.1f GB".format(gb) else "%.0f MB".format(bytes / 1_000_000.0)
}
