package it.persoft.lunaultra.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
                LunaApp(vm)
            }
        }
    }
}
