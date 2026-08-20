package it.persoft.lunaultra

import android.content.Context
import it.persoft.lunaultra.camera.CameraSession
import it.persoft.lunaultra.camera.CommandRegistry
import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.JsonFileStore
import it.persoft.lunaultra.gimbal.GimbalController
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.TcpClient
import it.persoft.lunaultra.net.WifiNetworkBinder
import it.persoft.lunaultra.timelapse.TimelapseEngine
import it.persoft.lunaultra.timelapse.TimelapseSequence
import kotlinx.coroutines.CoroutineScope
import java.io.File

/** Composizione manuale delle dipendenze: l'app è piccola e non richiede un framework DI. */
class AppContainer(context: Context, private val scope: CoroutineScope) {

    private val appContext = context.applicationContext

    val log = EventLog()

    val settingsStore = JsonFileStore(
        file = File(appContext.filesDir, "settings.json"),
        serializer = AppSettings.serializer(),
        default = AppSettings(),
        scope = scope,
    )

    val sequenceStore = JsonFileStore(
        file = File(appContext.filesDir, "sequence.json"),
        serializer = TimelapseSequence.serializer(),
        default = TimelapseSequence(),
        scope = scope,
    )

    val wifiBinder = WifiNetworkBinder(appContext, log)
    val tcpClient = TcpClient(log, wifiBinder)
    val registry = CommandRegistry(settingsStore.state)
    val session = CameraSession(log, tcpClient, scope, settingsStore.state, registry)
    val commands = LunaCommands(session, settingsStore.state, log)
    val gimbal = GimbalController(commands, settingsStore.state, log, scope)
    val engine = TimelapseEngine(commands, gimbal, log, scope)

    suspend fun load() {
        settingsStore.load()
        sequenceStore.load()
    }
}
