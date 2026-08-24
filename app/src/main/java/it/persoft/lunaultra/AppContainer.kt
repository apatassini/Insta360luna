package it.persoft.lunaultra

import android.content.Context
import it.persoft.lunaultra.camera.CameraSession
import it.persoft.lunaultra.camera.CodeProbe
import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.data.JsonFileStore
import it.persoft.lunaultra.gimbal.GimbalController
import it.persoft.lunaultra.gimbal.GimbalCalibrator
import it.persoft.lunaultra.gimbal.GimbalLimitMonitor
import it.persoft.lunaultra.media.Favorites
import it.persoft.lunaultra.media.CameraWriteProbe
import it.persoft.lunaultra.media.LocationDiary
import it.persoft.lunaultra.media.MediaRepository
import it.persoft.lunaultra.media.PositionLog
import it.persoft.lunaultra.stitch.PanoJobList
import it.persoft.lunaultra.stitch.PanoramaStitchJob
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.TcpClient
import it.persoft.lunaultra.net.WifiNetworkBinder
import it.persoft.lunaultra.preview.PreviewController
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

    val calibrationStore = JsonFileStore(
        file = File(appContext.filesDir, "gimbal_calibration.json"),
        serializer = GimbalCalibrationProfile.serializer(),
        default = GimbalCalibrationProfile(),
        scope = scope,
    )

    val favoritesStore = JsonFileStore(
        file = File(appContext.filesDir, "favorites.json"),
        serializer = Favorites.serializer(),
        default = Favorites(),
        scope = scope,
    )

    val wifiBinder = WifiNetworkBinder(appContext, log)
    val tcpClient = TcpClient(log, wifiBinder)
    val session = CameraSession(log, tcpClient, scope, settingsStore.state)
    val commands = LunaCommands(session, settingsStore.state, log)
    val probe = CodeProbe(session, log)
    val preview = PreviewController(session, commands, settingsStore.state, wifiBinder, log, scope)
    val gimbal = GimbalController(commands, settingsStore.state, calibrationStore.state, log, scope)
    val gimbalLimits = GimbalLimitMonitor(session.notifications, scope)
    val calibrator = GimbalCalibrator(gimbal, gimbalLimits, preview, calibrationStore, log, scope)
    val engine = TimelapseEngine(commands, gimbal, preview, settingsStore.state, calibrationStore.state, log, scope)

    /** Dove stava il telefono e quando: le coordinate da scrivere negli EXIF delle copie. */
    val positionStore = JsonFileStore(
        file = File(appContext.filesDir, "positions.json"),
        serializer = PositionLog.serializer(),
        default = PositionLog(),
        scope = scope,
    )
    val locationDiary = LocationDiary(appContext, positionStore, log)

    val media = MediaRepository(appContext, commands, settingsStore.state, wifiBinder, log, locationDiary)

    /** Risponde alla domanda «si può scrivere sulla scheda della camera?» provandoci. */
    val writeProbe = CameraWriteProbe(wifiBinder, log)

    /** Dagli scatti di una panoramica alla panoramica unita nella galleria del telefono. */
    val stitchJob = PanoramaStitchJob(appContext, media, log, locationDiary)

    /** Le panoramiche scattate e non ancora unite: scaricate, marcate, in attesa del via. */
    val panoJobStore = JsonFileStore(
        file = File(appContext.filesDir, "pano_jobs.json"),
        serializer = PanoJobList.serializer(),
        default = PanoJobList(),
        scope = scope,
    )

    suspend fun load() {
        settingsStore.load()
        sequenceStore.load()
        calibrationStore.load()
        favoritesStore.load()
        panoJobStore.load()
        positionStore.load()
    }
}
