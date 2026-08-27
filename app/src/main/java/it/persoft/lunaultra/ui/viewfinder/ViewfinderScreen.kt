package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.Panel
import it.persoft.lunaultra.ui.components.HudPill
import it.persoft.lunaultra.ui.components.PreviewSurface
import it.persoft.lunaultra.ui.formatClock
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Il mirino.
 *
 * L'impaginazione è quella di una camera: una fascia piena in alto con lo stato e le
 * regolazioni, l'immagine al centro, una fascia in basso con lo scatto e la ghiera delle
 * modalità. Le fasce sono opache e l'immagine ci sta dentro invece che sotto — così l'anteprima
 * si legge per quello che è, senza icone che le galleggiano addosso.
 *
 * Un tocco sull'immagine toglie le fasce e l'anteprima si allarga a tutto schermo: è lo stesso
 * gesto che si fa per controllare l'inquadratura prima di far partire una sequenza.
 *
 * In orizzontale la fascia di scatto passa a destra, dove arriva il pollice della mano che
 * regge il telefono.
 */
@Composable
fun ViewfinderScreen(
    viewModel: MainViewModel,
    onOpenPanel: (Panel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // Il selettore di sistema restituisce le foto nell'ordine in cui vengono toccate: toccarle
    // una, due, tre come sono state scattate è il modo di dare l'ordine all'unione.
    val photoPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris -> if (uris.isNotEmpty()) viewModel.stitchPickedPhotos(context, uris) }
    val settings by viewModel.settings.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val sequence by viewModel.sequence.collectAsState()
    val cameraSaving by viewModel.cameraSaving.collectAsState()
    val latestThumb by viewModel.latestShotThumb.collectAsState()
    val run by viewModel.runState.collectAsState()
    val ptz by viewModel.ptz.collectAsState()
    val moving by viewModel.gimbalMoving.collectAsState()
    val selfieEngaged by viewModel.selfieEngaged.collectAsState()
    val updateState by viewModel.update.collectAsState()
    val captureMode by viewModel.captureMode.collectAsState()
    val recordingSince by viewModel.recordingSinceMs.collectAsState()
    val wifiConnecting by viewModel.wifiConnecting.collectAsState()
    val photoCountdown by viewModel.photoCountdownSeconds.collectAsState()
    val calibration by viewModel.gimbalCalibration.collectAsState()
    val stitch by viewModel.stitchState.collectAsState()
    val stitchVitals by viewModel.stitchVitals.collectAsState()
    val panoJobs by viewModel.panoJobs.collectAsState()

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var gridVisible by rememberSaveable { mutableStateOf(false) }
    var fillScreen by rememberSaveable { mutableStateOf(false) }
    var modeSheetOpen by remember { mutableStateOf(false) }
    var photoSheetOpen by remember { mutableStateOf(false) }
    var videoSheetOpen by remember { mutableStateOf(false) }
    var jobsSheetOpen by remember { mutableStateOf(false) }

    LaunchedEffect(captureMode) {
        if (!captureMode.cameraMode.isPhoto) photoSheetOpen = false
        if (captureMode.cameraMode.isPhoto) videoSheetOpen = false
    }

    val connected = connection == ConnectionState.CONNECTED
    val recording = recordingSince > 0L || status.recording == true

    // Cronometro della ripresa: la camera dice i secondi solo quando la si interroga, ogni tre
    // secondi. Il conto locale parte dall'istante del comando e avanza da solo.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(recordingSince) {
        while (recordingSince > 0L) {
            nowMs = System.currentTimeMillis()
            delay(500)
        }
    }
    val elapsedSeconds = when {
        recordingSince > 0L -> ((nowMs - recordingSince) / 1000L).toInt()
        else -> status.captureSeconds ?: 0
    }

    val sequenceReady = sequence.isRunnable
    // Questa modalità, adesso, percorrerà i punti: o perché è una voce «Sequenza…», o perché i
    // punti ci sono e la modalità sa cosa farne. Da qui dipende cosa mostra il pulsante di
    // scatto — l'avanzamento del percorso invece del pallino di registrazione.
    val followsPath = captureMode.plansPanorama ||
        (captureMode.pathBehaviour != null && (captureMode.usesSequence || sequenceReady))
    val shutterReady = connected &&
        (!captureMode.usesSequence || sequenceReady || run.running) &&
        (!captureMode.plansPanorama || calibration.isValid || run.running)
    val shutterActive = if (followsPath) run.running else recording || photoCountdown > 0
    val shutterProgress = if (run.running) run.overallProgress else 0f

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Luna.Bg)) {
        val landscape = maxWidth > maxHeight
        val layoutDirection = LocalLayoutDirection.current
        val insets = WindowInsets.safeDrawing.asPaddingValues()
        val topInset = insets.calculateTopPadding()
        val bottomInset = insets.calculateBottomPadding()
        val endInset = insets.calculateEndPadding(layoutDirection)

        // Le fasce spingono dentro l'immagine invece di coprirla; quando spariscono, l'anteprima
        // si riprende lo schermo con un'animazione sola.
        val topPad by animateDpAsState(
            targetValue = if (chromeVisible) topInset + TopBandHeight else 0.dp,
            label = "topBand",
        )
        val bottomPad by animateDpAsState(
            targetValue = if (chromeVisible && !landscape) bottomInset + BottomBandHeight else 0.dp,
            label = "bottomBand",
        )
        val endPad by animateDpAsState(
            targetValue = if (chromeVisible && landscape) endInset + SideBandWidth else 0.dp,
            label = "sideBand",
        )
        val areaPadding = Modifier.padding(top = topPad, bottom = bottomPad, end = endPad)

        PreviewSurface(
            state = preview,
            onSurfaceChanged = viewModel::attachPreviewSurface,
            fillScreen = fillScreen,
            modifier = Modifier.fillMaxSize().then(areaPadding),
        )

        if (gridVisible) {
            GridOverlay(modifier = Modifier.fillMaxSize().then(areaPadding))
        }

        // Il tocco singolo chiude la maschera aperta, e basta: capitava di sfiorare lo
        // schermo e ritrovarsi la foto a tutto schermo senza capire perché. Lo schermo
        // intero è una scelta, e si fa con il doppio tocco.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            when {
                                modeSheetOpen -> modeSheetOpen = false
                                photoSheetOpen -> photoSheetOpen = false
                                videoSheetOpen -> videoSheetOpen = false
                                jobsSheetOpen -> jobsSheetOpen = false
                                !chromeVisible -> chromeVisible = true
                                else -> Unit
                            }
                        },
                        onDoubleTap = { chromeVisible = !chromeVisible },
                    )
                },
        )

        // Tutto ciò che sta sull'immagine vive dentro l'area libera fra le fasce.
        Box(modifier = Modifier.fillMaxSize().then(areaPadding)) {
            if (chromeVisible) {
                StatColumn(
                    freeSpace = status.freeSpaceBytes,
                    batteryPercent = status.batteryPercent,
                    recordingLabel = if (recording) formatClock(elapsedSeconds) else null,
                    modifier = Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 12.dp),
                )

                // Questa riga dice cosa succede premendo lo scatto: è l'informazione che
                // cambia il risultato, e si tocca per andare dove si cambia.
                if (captureMode.plansPanorama) {
                    // La panoramica dell'app parte da qui, quindi da qui si deve leggere *quale*
                    // parte: tutta la corsa oppure i gradi scelti.
                    InfoPill(
                        text = if (sequence.panoramaSpherical) {
                            "sferica"
                        } else {
                            "${sequence.panoramaHorizontalDegrees.roundToInt()}°"
                        },
                        icon = LunaIcons.Panorama,
                        color = captureMode.color,
                        onClick = { onOpenPanel(Panel.PANORAMA) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
                    )
                } else if (captureMode.hasPanoAspect) {
                    // Nella panoramica della camera l'unica scelta è fra sferica e 2:1.
                    val spherical = settings.panoAspect == LunaProtocolCodes.PanoAspect.SPHERE_360
                    InfoPill(
                        text = if (spherical) "sferica 360°" else "panorama 2:1",
                        icon = LunaIcons.Panorama,
                        color = captureMode.color,
                        onClick = viewModel::togglePanoAspect,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
                    )
                } else if (captureMode.pathBehaviour != null && sequence.waypoints.size >= 2) {
                    // Con dei punti memorizzati questa riga serve alla durata del percorso, che
                    // è il numero che si aggiusta fra una passata e l'altra. L'informazione
                    // della modalità torna appena i punti non ci sono più.
                    PathDock(
                        waypointCount = sequence.waypoints.size,
                        totalSeconds = sequence.effectiveTotalSeconds(),
                        accent = captureMode.color,
                        onNudge = viewModel::nudgeTotalDuration,
                        onOpenAutomations = { onOpenPanel(Panel.SEQUENCE) },
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
                    )
                } else {
                    infoPillText(captureMode, sequence.settleSeconds, sequence.intervalSeconds, sequence.effectiveTotalSeconds())
                        ?.let { pill ->
                            InfoPill(
                                text = pill,
                                icon = LunaIcons.MotionTimelapse,
                                color = captureMode.color,
                                onClick = { onOpenPanel(Panel.SEQUENCE) },
                                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 70.dp),
                            )
                        }
                }

                GimbalDock(
                    enabled = connected,
                    moving = moving,
                    panDegrees = ptz.pan,
                    tiltDegrees = ptz.tilt,
                    positionFromCamera = ptz.fromCamera,
                    speedPercent = settings.gimbal.manualSpeedPercent,
                    hardwareSpeedLevel = settings.gimbal.hardwareSpeedLevel,
                    onSpeedChange = viewModel::setManualSpeed,
                    onHardwareSpeedChange = viewModel::setGimbalHardwareSpeed,
                    onVector = viewModel::jogVector,
                    onRelease = viewModel::jogStop,
                    onStop = viewModel::jogStop,
                    onZero = viewModel::zeroPosition,
                    onSelfie = viewModel::selfieTurn,
                    selfieEngaged = selfieEngaged,
                    onBootZero = viewModel::returnToBootZero,
                    onCaptureWaypoint = viewModel::captureWaypoint,
                    compact = landscape,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 10.dp),
                )

                ZoomDock(
                    selected = settings.photo.zoomScale,
                    enabled = connected && !run.running,
                    onSelect = viewModel::setPhotoZoom,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = 12.dp, bottom = 14.dp),
                )
            }

            val note = previewNote(preview.active, preview.framesDecoded, preview.message)
            when {
                !connected -> ConnectCta(
                    connection = connection,
                    searchingWifi = wifiConnecting,
                    onConnect = viewModel::connect,
                    modifier = Modifier.align(Alignment.Center),
                )

                note != null -> PreviewStatusNote(
                    text = note,
                    modifier = Modifier.align(Alignment.Center),
                )
            }

            if (photoCountdown > 0) {
                PhotoCountdown(photoCountdown, modifier = Modifier.align(Alignment.Center))
            }

            if (photoSheetOpen && chromeVisible) {
                PhotoControlsSheet(
                    settings = settings.photo,
                    onProMode = viewModel::setPhotoProMode,
                    onTimer = viewModel::setPhotoTimer,
                    onResolution = viewModel::setPhotoResolution,
                    onRawCapture = viewModel::setPhotoRawCapture,
                    onZoom = viewModel::setPhotoZoom,
                    onBrightness = viewModel::setPhotoBrightness,
                    onExposureBias = viewModel::setPhotoExposureBias,
                    onWhiteBalance = viewModel::setPhotoWhiteBalance,
                    onClose = { photoSheetOpen = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }

            if (jobsSheetOpen && chromeVisible) {
                PanoJobsSheet(
                    jobs = panoJobs.jobs,
                    busy = stitch is it.persoft.lunaultra.stitch.StitchUiState.Working,
                    deleteOnFinish = settings.deleteJobAfterStitch,
                    onDeleteOnFinish = viewModel::setDeleteJobAfterStitch,
                    onRun = {
                        jobsSheetOpen = false
                        viewModel.runPanoJob(it)
                    },
                    onPrepare = {
                        jobsSheetOpen = false
                        viewModel.preparePanoJob(it)
                    },
                    onRunAll = {
                        jobsSheetOpen = false
                        viewModel.runAllPanoJobs()
                    },
                    face = viewModel::jobFace,
                    onCancel = viewModel::cancelPanoJob,
                    onClose = { jobsSheetOpen = false },
                    // A tutta larghezza e appoggiato in fondo: i lavori sono parecchi e l'elenco
                    // vuole spazio, non un riquadro stretto in mezzo allo schermo.
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 4.dp),
                )
            }

            if (videoSheetOpen && chromeVisible) {
                VideoControlsSheet(
                    settings = settings.video,
                    mode = captureMode.cameraMode,
                    onProfile = viewModel::setVideoProfile,
                    onProMode = viewModel::setVideoProMode,
                    onIso = viewModel::setVideoIso,
                    onShutter = viewModel::setVideoShutter,
                    onExposureBias = viewModel::setVideoExposureBias,
                    onWhiteBalance = viewModel::setVideoWhiteBalance,
                    onColorMode = viewModel::setVideoColorMode,
                    onFilter = viewModel::setVideoFilter,
                    onFilterIntensity = viewModel::setVideoFilterIntensity,
                    onSharpness = viewModel::setVideoSharpness,
                    onClose = { videoSheetOpen = false },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }

            UpdateNotice(
                state = updateState,
                onDismiss = viewModel::dismissUpdateNotice,
                modifier = Modifier.align(Alignment.Center),
            )

            if (modeSheetOpen && chromeVisible) {
                ModeSheet(
                    selected = captureMode,
                    sequenceReady = sequenceReady,
                    onSelect = viewModel::setCaptureMode,
                    onOpenPanorama = { onOpenPanel(Panel.PANORAMA) },
                    onDismiss = { modeSheetOpen = false },
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp),
                )
            }
        }

        if (chromeVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Luna.Band)
                    .padding(top = topInset),
            ) {
                ViewfinderTopBar(
                    onOpenModeSheet = {
                        modeSheetOpen = !modeSheetOpen
                        photoSheetOpen = false
                        videoSheetOpen = false
                    },
                    connection = connection,
                    mode = captureMode,
                    badgeDetail = badgeDetailFor(captureMode, sequence.waypoints.size, status),
                    previewActive = preview.active,
                    gridVisible = gridVisible,
                    fillScreen = fillScreen,
                    onToggleConnection = { if (connected) viewModel.disconnect() else viewModel.connect() },
                    onTogglePreview = viewModel::togglePreview,
                    onToggleGrid = { gridVisible = !gridVisible },
                    onToggleFill = { fillScreen = !fillScreen },
                    onHideChrome = { chromeVisible = false },
                    onOpenSettings = { onOpenPanel(Panel.SETTINGS) },
                    onOpenSequence = { onOpenPanel(Panel.SEQUENCE) },
                    onOpenGallery = { onOpenPanel(Panel.GALLERY) },
                    onOpenDiagnostics = { onOpenPanel(Panel.DIAGNOSTICS) },
                    onRefreshStatus = viewModel::refreshStatus,
                    onPickPhotos = { photoPicker.launch(arrayOf("image/*")) },
                    onShareLog = { viewModel.shareLog(context) },
                )
            }

            val captureBar: @Composable () -> Unit = {
                CaptureBar(
                    selected = captureMode,
                    active = shutterActive,
                    progress = shutterProgress,
                    shutterReady = shutterReady,
                    onShutter = viewModel::onShutter,
                    waypointCount = sequence.waypoints.size,
                    onCaptureWaypoint = viewModel::captureWaypoint,
                    onRemoveLastWaypoint = viewModel::removeLastWaypoint,
                    onOpenCameraSettings = {
                        if (captureMode.cameraMode.isPhoto) {
                            photoSheetOpen = !photoSheetOpen
                            videoSheetOpen = false
                            modeSheetOpen = false
                        } else {
                            videoSheetOpen = !videoSheetOpen
                            photoSheetOpen = false
                            modeSheetOpen = false
                        }
                    },
                    onOpenJobs = {
                        jobsSheetOpen = !jobsSheetOpen
                        photoSheetOpen = false
                        videoSheetOpen = false
                        modeSheetOpen = false
                    },
                    onOpenGallery = { onOpenPanel(Panel.GALLERY) },
                    vertical = landscape,
                    latestThumb = latestThumb,
                    pendingJobs = panoJobs.jobs.size,
                )
            }

            if (landscape) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .background(Luna.Band)
                        .padding(top = topInset + TopBandHeight, end = endInset),
                ) {
                    captureBar()
                }
            } else {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Luna.Band)
                        .padding(bottom = bottomInset),
                ) {
                    captureBar()
                }
            }
        }

        // L'avanzamento sta in cima e si vede sempre, non solo a comandi nascosti: una
        // panoramica da ventiquattro scatti dura due minuti, e per due minuti chi guarda deve
        // sapere a che punto è e poter fermare. Sotto, l'esito dell'unione — che è la cosa che
        // può andare storta *dopo* che gli scatti sono riusciti, e che finiva solo nel log.
        // A destra dei distintivi di scheda e batteria, mai sopra: la colonna parte dopo lo
        // spazio che i distintivi occupano davvero — «175,3 GB» è largo — e le carte si
        // stringono per starci, invece di fidarsi di una larghezza fissa che non bastava.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth()
                .padding(
                    top = topInset + if (chromeVisible) TopBandHeight + 8.dp else 12.dp,
                    start = StatColumnReservedWidth,
                    end = 12.dp,
                ),
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (run.running) {
                RunCard(run = run, mode = captureMode, saving = cameraSaving, onStop = viewModel::emergencyStop)
            }
            StitchCard(
                state = stitch,
                onDismiss = viewModel::clearStitchState,
                vitals = stitchVitals,
            )
        }
    }
}

/** Pastiglia informativa sopra la fascia di scatto: il numero che conta in questa modalità. */
@Composable
private fun InfoPill(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    HudPill(
        text = text,
        icon = icon,
        tint = color,
        onClick = onClick,
        modifier = modifier,
    )
}

/** Cosa scrivere nella pastiglia: ogni modalità ha un tempo che ne decide il risultato. */
private fun infoPillText(
    mode: CaptureMode,
    settleSeconds: Float,
    intervalSeconds: Float,
    totalSeconds: Float,
): String? = when (mode) {
    CaptureMode.SEQUENZA_FOTO -> "attesa ${trim(settleSeconds)} s"
    CaptureMode.TIMELAPSE, CaptureMode.SEQUENZA_TL -> "intervallo ${trim(intervalSeconds)} s"
    CaptureMode.SEQUENZA_VIDEO -> "durata ${totalSeconds.roundToInt()} s"
    // La panoramica dell'app ha una pastiglia sua, scritta dove si legge cosa partirà.
    CaptureMode.FOTO, CaptureMode.VIDEO, CaptureMode.PURE_VIDEO,
    CaptureMode.SLOW_MOTION, CaptureMode.PANORAMA, CaptureMode.PANORAMICA_APP -> null
}

/**
 * Quanto spazio riservano in orizzontale i distintivi di stato in alto a sinistra: il più
 * largo è quello della scheda («175,3 GB»). Le carte di avanzamento partono da qui in poi.
 */
private val StatColumnReservedWidth = 132.dp

private fun trim(value: Float): String =
    if (value == value.toInt().toFloat()) value.toInt().toString() else "%.1f".format(value)

/** Perché non si vede niente: spenta, in avvio, o ferma con un motivo da leggere. */
private fun previewNote(active: Boolean, framesDecoded: Long, message: String?): String? = when {
    !active -> "Anteprima spenta"
    framesDecoded > 0L -> null
    else -> message ?: "Anteprima in avvio…"
}
