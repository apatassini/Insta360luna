package it.persoft.lunaultra.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import it.persoft.lunaultra.ui.theme.LunaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Una sequenza dura minuti e non si tocca lo schermo mentre gira: senza questo, il
        // telefono si spegne a metà ripresa e i comandi restano a carico di un'app in pausa.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            LunaTheme {
                val vm: MainViewModel = viewModel()
                AskRuntimePermissions(onReady = vm::autoConnect)
                LunaApp(vm)
            }
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
