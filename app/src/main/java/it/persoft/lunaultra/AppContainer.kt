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
import kotlin.math.abs

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

    /**
     * L'unione delle panoramiche, che è anche il nostro strumento di taratura del gimbal.
     *
     * Il gimbal non ha ritorno di posizione: la Luna Ultra manda la notifica 8302 con due soli
     * bit — i finecorsa — e gli altri sette campi restano a zero sempre. Quindi la posizione la
     * calcoliamo noi, integrando velocità per tempo, e la scala di quella velocità nasceva da un
     * numero di catalogo mai verificato. Su un esemplare vero sbagliava del 31%.
     *
     * L'unica misura affidabile degli angoli veri sono le foto, dove il righello è il campo
     * visivo dell'obiettivo. Quindi ogni panoramica unita corregge la taratura: si compone con
     * quella che c'è già, così ogni panoramica successiva la rifinisce invece di ripartire.
     */
    val stitchJob = PanoramaStitchJob(
        context = appContext,
        media = media,
        log = log,
        locations = locationDiary,
        onGimbalScale = { pan, tilt ->
            val before = calibrationStore.state.value
            if (before.isValid && (abs(pan - 1f) >= 0.01f || abs(tilt - 1f) >= 0.01f)) {
                calibrationStore.update { it.withAngularScale(pan, tilt) }
                val after = calibrationStore.state.value
                log.info(
                    "TARATURA DEL GIMBAL CORRETTA DALLE FOTO",
                    "Verticale ×%.3f → ×%.3f · orizzontale ×%.3f → ×%.3f\n".format(
                        before.tiltAngularScale,
                        after.tiltAngularScale,
                        before.panAngularScale,
                        after.panAngularScale,
                    ) +
                        "Misurato sulle foto appena unite: è l'unico righello che abbiamo, " +
                        "perché la camera la sua posizione non la dice.",
                )
            }
        },
        onFrameCrop = { crop ->
            // Si sostituisce invece di comporsi: la misura e' sempre riferita al campo visivo
            // di catalogo, quindi ogni unione ne produce lo stesso valore assoluto.
            if (kotlin.math.abs(calibrationStore.state.value.frameCropFactor - crop) > 0.005f) {
                calibrationStore.update { it.withFrameCrop(crop) }
                log.info(
                    "RITAGLIO DEL FOTOGRAMMA AGGIORNATO",
                    "La camera consegna il %.1f%% del fotogramma dichiarato: e' il numero su cui "
                        .format(crop * 100f) +
                        "il pianificatore distanziera' gli scatti d'ora in avanti.",
                )
            }
        },
        gimbalScaleNow = {
            val profile = calibrationStore.state.value
            profile.panAngularScale to profile.tiltAngularScale
        },
    )

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
