package it.persoft.lunaultra.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import it.persoft.lunaultra.R

/**
 * Tiene viva la connessione con la camera mentre l'app è in secondo piano.
 *
 * Non è un servizio "di comodo": da Android 12 un'app che finisce in background viene
 * **congelata** dopo pochi secondi, e con lei si ferma il keep-alive della sessione. La camera
 * non vede più il battito e chiude — che è esattamente il «si disconnette quando cambio app».
 * Un servizio in primo piano è l'unico modo documentato di non essere congelati.
 *
 * Con la sessione tiene anche il Wi-Fi sveglio e la CPU accesa: una sequenza dura minuti, e
 * durante una ripresa lo schermo può spegnersi.
 */
class LunaConnectionService : Service() {

    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TEXT
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(text))
        acquireLocks()
        // Riavviarlo da solo non servirebbe: senza la sessione dell'app è un servizio vuoto.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Connessione alla camera", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Mantiene attiva la sessione con la Luna Ultra mentre l'app è in secondo piano"
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Luna Ultra")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pending)
            .build()
    }

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = applicationContext.getSystemService(WifiManager::class.java)
            wifiLock = wifi?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "luna:wifi")
                ?.apply { runCatching { acquire() } }
        }
        if (wakeLock == null) {
            val power = getSystemService(PowerManager::class.java)
            wakeLock = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "luna:session")
                ?.apply { runCatching { acquire(MAX_LOCK_MS) } }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    companion object {
        private const val CHANNEL_ID = "luna_connection"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TEXT = "text"
        private const val DEFAULT_TEXT = "Connessa — la sessione resta aperta"

        /** Tetto del wake lock: una sequenza lunga, non una notte intera per una dimenticanza. */
        private const val MAX_LOCK_MS = 4L * 60L * 60L * 1000L

        /** Avvia il servizio, o ne aggiorna il testo se è già in piedi. */
        fun start(context: Context, text: String = DEFAULT_TEXT) {
            val intent = Intent(context, LunaConnectionService::class.java).putExtra(EXTRA_TEXT, text)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, LunaConnectionService::class.java)) }
        }
    }
}
