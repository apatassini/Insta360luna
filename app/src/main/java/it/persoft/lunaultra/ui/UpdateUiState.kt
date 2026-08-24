package it.persoft.lunaultra.ui

/**
 * A che punto è l'aggiornamento, per poterlo mostrare.
 *
 * Prima era una sequenza di messaggi che passavano e sparivano: «Controllo aggiornamenti…» e
 * poi, dopo un silenzio lungo quanto lo scaricamento di quattro megabyte su una rete mobile,
 * la richiesta di installare. Nel mezzo non succedeva niente di visibile, e un'attesa senza
 * segni è indistinguibile da un blocco.
 */
sealed interface UpdateUiState {

    /** Niente in corso e niente da dire. */
    data object Idle : UpdateUiState

    data class Checking(val branch: String) : UpdateUiState

    /**
     * [total] negativo significa dimensione non dichiarata dal server: in quel caso c'è solo
     * il conteggio dei megabyte, senza percentuale.
     */
    data class Downloading(val branch: String, val downloaded: Long, val total: Long) : UpdateUiState {
        val fraction: Float? get() = if (total > 0L) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
        val percent: Int? get() = fraction?.let { (it * 100f).toInt() }
    }

    data class ReadyToInstall(
        val branch: String,
        val commitSha: String,
        /** Quando è stata pubblicata la build: è la data che si legge, non il commit. */
        val publishedAtMs: Long? = null,
    ) : UpdateUiState

    data class UpToDate(val branch: String) : UpdateUiState

    data class Failed(val branch: String, val reason: String) : UpdateUiState
}

/**
 * «domenica 24 agosto, 09:55» — come si presenta una build a una persona.
 *
 * Il commit resta nei log per chi sviluppa; chi aggiorna vuole sapere quanto è fresca.
 */
fun buildDateLabel(timeMs: Long): String =
    java.text.SimpleDateFormat("EEEE d MMMM, HH:mm", java.util.Locale.getDefault())
        .format(java.util.Date(timeMs))
