package it.persoft.lunaultra.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.IntentCompat
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

/**
 * Installa l'APK scaricato passando dal `PackageInstaller`, non da un intent di visualizzazione.
 *
 * La differenza è tutta nella schermata di conferma. Aprire l'APK con `ACTION_VIEW` — che è la
 * strada ovvia, e quella che usano quasi tutti — significa consegnare il file al Package
 * Installer, che chiede conferma **ogni volta**, a ogni aggiornamento, per sempre. Non dipende
 * dalla chiave con cui si firma: non c'è certificato che tolga quella schermata.
 *
 * Con una sessione di installazione, da Android 12 il sistema accetta di saltarla, a quattro
 * condizioni: che l'app aggiorni se stessa, che sia lei ad aver installato la versione
 * precedente, che la firma sia la stessa e che non sia un ritorno indietro. La seconda condizione
 * è quella che si conquista sul campo: la prima volta la conferma compare lo stesso — è
 * l'installazione che ci rende «installer of record» — e da lì in avanti gli aggiornamenti
 * passano in silenzio.
 *
 * Sotto Android 12 la conferma resta comunque, e qualche ROM ne mette una propria che non si
 * governa da qui.
 */
object ApkInstaller {

    const val AZIONE_ESITO = "it.persoft.lunaultra.ESITO_INSTALLAZIONE"

    private val _esiti = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Messaggi leggibili sull'esito, per il log e per lo schermo. */
    val esiti: SharedFlow<String> = _esiti

    internal fun segnala(messaggio: String) {
        _esiti.tryEmit(messaggio)
    }

    /**
     * @return `null` se la sessione è partita, altrimenti il motivo per cui non è partita.
     *   Che sia partita non vuol dire che sia finita: l'esito arriva su [esiti].
     */
    fun installa(context: Context, apk: File): String? {
        val app = context.applicationContext
        if (!apk.isFile || apk.length() <= 0L) return "il file dell'aggiornamento non c'è più"
        return runCatching {
            val installer = app.packageManager.packageInstaller
            val parametri = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            parametri.setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                parametri.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
            val idSessione = installer.createSession(parametri)
            installer.openSession(idSessione).use { sessione ->
                sessione.openWrite(NOME_PEZZO, 0, apk.length()).use { uscita ->
                    apk.inputStream().use { it.copyTo(uscita) }
                    // Senza fsync i byte possono essere ancora in memoria quando la sessione
                    // viene confermata, e il sistema si ritrova un pacchetto tronco.
                    sessione.fsync(uscita)
                }
                sessione.commit(intentDiEsito(app, idSessione).intentSender)
            }
            null
        }.getOrElse { it.message ?: "installazione non avviabile" }
    }

    private fun intentDiEsito(context: Context, idSessione: Int): PendingIntent {
        val intento = Intent(AZIONE_ESITO).setPackage(context.packageName)
        // Mutabile per forza: è il sistema a riempirlo con lo stato e, quando serve, con
        // l'intent della schermata di conferma.
        val flag = PendingIntent.FLAG_UPDATE_CURRENT or
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, idSessione, intento, flag)
    }

    private const val NOME_PEZZO = "luna"
}

/** Raccoglie l'esito della sessione di installazione. Registrato nel manifest, non esportato. */
class EsitoInstallazione : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (val stato = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Il sistema non se l'è sentita di installare da solo: la prima volta è sempre
                // così, e capita anche quando manca "Consenti da questa origine". L'intent che
                // ci passa è la schermata da mostrare.
                val conferma = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                if (conferma == null) {
                    ApkInstaller.segnala("Android chiede conferma ma non dice dove: riprova.")
                    return
                }
                conferma.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(conferma) }
                    .onFailure { ApkInstaller.segnala("Conferma non apribile: ${it.message}") }
            }

            PackageInstaller.STATUS_SUCCESS ->
                ApkInstaller.segnala("Aggiornamento installato.")

            else -> {
                val motivo = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                ApkInstaller.segnala("Installazione non riuscita (codice $stato): ${motivo ?: "senza motivo"}")
            }
        }
    }
}
