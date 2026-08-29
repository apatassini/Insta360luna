package it.persoft.lunaultra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import it.persoft.lunaultra.update.ApkInstaller
import androidx.lifecycle.viewmodel.compose.viewModel
import it.persoft.lunaultra.ui.theme.LunaTheme
import java.io.File

class MainActivity : ComponentActivity() {

    private var pendingUpdate: File? = null
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val update = pendingUpdate ?: return@registerForActivityResult
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || packageManager.canRequestPackageInstalls()) {
            pendingUpdate = null
            openInstaller(update)
        } else {
            Toast.makeText(this, "Autorizzazione installazione non concessa", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Una sequenza dura minuti e non si tocca lo schermo mentre gira: senza questo, il
        // telefono si spegne a metà ripresa e i comandi restano a carico di un'app in pausa.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LunaTheme {
                val vm: MainViewModel = viewModel()
                AskRuntimePermissions(onReady = { vm.checkForUpdate(::installUpdate) })
                LunaApp(vm)
            }
        }
    }

    private fun installUpdate(apk: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingUpdate = apk
            installPermission.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                )
            )
            return
        }
        openInstaller(apk)
    }

    /**
     * L'installazione passa da una sessione del `PackageInstaller`, non da `ACTION_VIEW`.
     *
     * È l'unica strada che permette ad Android 12 e successivi di installare l'aggiornamento
     * senza chiedere conferma: vedi [ApkInstaller]. Quando il sistema la conferma la vuole
     * comunque — la prima volta, sempre — arriva al ricevitore, che apre la schermata.
     */
    private fun openInstaller(apk: File) {
        ApkInstaller.installa(this, apk)?.let { motivo ->
            Toast.makeText(this, "Installazione non disponibile: $motivo", Toast.LENGTH_LONG).show()
        }
    }
}

/**
 * Chiede il permesso delle notifiche, che serve a mostrare quella del servizio che tiene aperta
 * la sessione. Negarlo non impedisce alla connessione di restare viva: sparisce solo la riga
 * nella tendina che dice che c'è.
 */
@Composable
private fun AskRuntimePermissions(onReady: () -> Unit) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // Anche se le notifiche vengono negate, la connessione Wi-Fi può partire. Se invece
        // manca il permesso Wi-Fi, il binder restituisce un errore leggibile e il tasto resta.
        onReady()
    }
    LaunchedEffect(Unit) {
        val requested = buildList {
            // Anche da Android 13 la scansione SSID/BSSID richiede ACCESS_FINE_LOCATION.
            // NEARBY_WIFI_DEVICES da solo consente la richiesta della rete, ma non permette
            // di trasformare il filtro generico nel punto di accesso Luna esatto.
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        val missing = requested.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) onReady() else launcher.launch(missing.toTypedArray())
    }
}
