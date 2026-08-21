package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.Panel
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.components.GlassPanel
import it.persoft.lunaultra.ui.components.PreviewSurface
import it.persoft.lunaultra.ui.formatClock
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlinx.coroutines.delay

/**
 * Il mirino: l'anteprima a tutto schermo con i comandi sopra.
 *
 * L'impianto è quello di un'app di ripresa, e non per somiglianza: mentre si inquadra si guarda
 * l'immagine, quindi tutto ciò che serve dev'essere sopra l'immagine e raggiungibile con il
 * pollice, non dietro una scheda. Un tocco sull'anteprima nasconde i comandi e lascia solo
 * l'inquadratura — tranne l'avanzamento di una sequenza in corso, che resta perché il suo STOP
 * deve restare.
 *
 * In orizzontale i comandi di ripresa passano sul lato destro: è il lato in cui sta la mano che
 * regge il telefono quando lo si gira, e in orizzontale l'altezza è il bene scarso.
 */
@Composable
fun ViewfinderScreen(
    viewModel: MainViewModel,
    onOpenPanel: (Panel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val sequence by viewModel.sequence.collectAsState()
    val run by viewModel.runState.collectAsState()
    val ptz by viewModel.ptz.collectAsState()
    val moving by viewModel.gimbalMoving.collectAsState()
    val captureMode by viewModel.captureMode.collectAsState()
    val recordingSince by viewModel.recordingSinceMs.collectAsState()

    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var gridVisible by rememberSaveable { mutableStateOf(false) }
    var fillScreen by rememberSaveable { mutableStateOf(false) }
    var dockVisible by rememberSaveable { mutableStateOf(true) }

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
    val shutterEnabled = connected && (!captureMode.usesSequence || sequenceReady || run.running)
    val shutterActive = if (captureMode.usesSequence) run.running else recording
    val shutterProgress = if (captureMode.usesSequence && run.running) run.overallProgress else 0f

    BoxWithConstraints(modifier = modifier.fillMaxSize().background(Color.Black)) {
        val landscape = maxWidth > maxHeight

        PreviewSurface(
            state = preview,
            onSurfaceChanged = viewModel::attachPreviewSurface,
            fillScreen = fillScreen,
            modifier = Modifier.fillMaxSize(),
        )

        if (gridVisible) GridOverlay(modifier = Modifier.fillMaxSize())

        // Il tocco sull'anteprima mostra e nasconde i comandi: sta sotto ai comandi stessi,
        // quindi premere un pulsante non li fa sparire.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { chromeVisible = !chromeVisible })
                },
        )

        if (chromeVisible) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(190.dp)
                    .background(Brush.verticalGradient(listOf(Luna.ScrimStrong, Luna.ScrimNone))),
            )
            if (landscape) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .width(190.dp)
                        .background(Brush.horizontalGradient(listOf(Luna.ScrimNone, Luna.ScrimStrong))),
                )
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(Brush.verticalGradient(listOf(Luna.ScrimNone, Luna.ScrimStrong))),
                )
            }
        }

        val middle: @Composable BoxScope.() -> Unit = {
            QuickBar(
                previewActive = preview.active,
                previewEnabled = connected,
                onTogglePreview = viewModel::togglePreview,
                gridVisible = gridVisible,
                onToggleGrid = { gridVisible = !gridVisible },
                fillScreen = fillScreen,
                onToggleFill = { fillScreen = !fillScreen },
                dockVisible = dockVisible,
                onToggleDock = { dockVisible = !dockVisible },
                onHideChrome = { chromeVisible = false },
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 8.dp),
            )

            if (dockVisible) {
                GimbalDock(
                    enabled = connected,
                    codeKnown = settings.gimbal.isControlCodeKnown,
                    moving = moving,
                    panDegrees = ptz.pan,
                    tiltDegrees = ptz.tilt,
                    positionFromCamera = ptz.fromCamera,
                    speedPercent = settings.gimbal.manualSpeedPercent,
                    onSpeedChange = viewModel::setManualSpeed,
                    onVector = viewModel::jogVector,
                    onJog = viewModel::jogStart,
                    onRelease = viewModel::jogStop,
                    onStop = viewModel::jogStop,
                    onZero = viewModel::zeroPosition,
                    onCaptureWaypoint = viewModel::captureWaypoint,
                    onOpenDiagnostics = { onOpenPanel(Panel.DIAGNOSTICS) },
                    onClose = { dockVisible = false },
                    compact = landscape,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 8.dp),
                )
            }

            val note = previewNote(preview.active, preview.framesDecoded, preview.message)
            when {
                !connected -> ConnectCta(
                    connection = connection,
                    host = settings.host,
                    onConnect = viewModel::connect,
                    modifier = Modifier.align(Alignment.Center),
                )

                note != null -> PreviewStatusNote(
                    text = note,
                    modifier = Modifier.align(Alignment.Center),
                )
            }
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            val hud: @Composable () -> Unit = {
                ViewfinderHud(
                    connection = connection,
                    status = status,
                    subtitle = viewfinderSubtitle(captureMode, sequence.waypoints.size, preview),
                    recordingLabel = if (recording) formatClock(elapsedSeconds) else null,
                    onToggleConnection = { if (connected) viewModel.disconnect() else viewModel.connect() },
                    onOpenSettings = { onOpenPanel(Panel.SETTINGS) },
                    onOpenSequence = { onOpenPanel(Panel.SEQUENCE) },
                    onOpenDiagnostics = { onOpenPanel(Panel.DIAGNOSTICS) },
                    onRefreshStatus = viewModel::refreshStatus,
                    onShareLog = { viewModel.shareLog(context) },
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            val captureBar: @Composable () -> Unit = {
                CaptureBar(
                    selected = captureMode,
                    onSelect = viewModel::setCaptureMode,
                    active = shutterActive,
                    progress = shutterProgress,
                    shutterEnabled = shutterEnabled,
                    onShutter = viewModel::onShutter,
                    waypointCount = sequence.waypoints.size,
                    onCaptureWaypoint = viewModel::captureWaypoint,
                    onOpenSequence = { onOpenPanel(Panel.SEQUENCE) },
                    vertical = landscape,
                )
            }

            if (landscape) {
                Row(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                        hud()
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), content = middle)
                    }
                    captureBar()
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    hud()
                    Box(modifier = Modifier.weight(1f).fillMaxWidth(), content = middle)
                    captureBar()
                }
            }
        }

        // L'avanzamento della sequenza sopravvive ai comandi nascosti: il suo STOP è l'unico
        // comando che non si può dover cercare.
        if (run.running) {
            RunCard(
                run = run,
                mode = captureMode,
                onStop = viewModel::emergencyStop,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .safeDrawingPadding()
                    .padding(top = if (chromeVisible) 104.dp else 12.dp),
            )
        }
    }
}

/** La colonna dei comandi rapidi dell'anteprima, sul lato che resta libero. */
@Composable
private fun QuickBar(
    previewActive: Boolean,
    previewEnabled: Boolean,
    onTogglePreview: () -> Unit,
    gridVisible: Boolean,
    onToggleGrid: () -> Unit,
    fillScreen: Boolean,
    onToggleFill: () -> Unit,
    dockVisible: Boolean,
    onToggleDock: () -> Unit,
    onHideChrome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier = modifier, contentPadding = 6.dp, verticalSpacing = 6.dp) {
        HudIconButton(
            icon = if (previewActive) LunaIcons.Video else LunaIcons.VideoOff,
            contentDescription = if (previewActive) "Spegni l'anteprima" else "Accendi l'anteprima",
            onClick = onTogglePreview,
            selected = previewActive,
            enabled = previewEnabled,
            size = 40.dp,
        )
        HudIconButton(
            icon = if (gridVisible) LunaIcons.Grid else LunaIcons.GridOff,
            contentDescription = "Griglia dei terzi",
            onClick = onToggleGrid,
            selected = gridVisible,
            size = 40.dp,
        )
        HudIconButton(
            icon = if (fillScreen) LunaIcons.Fill else LunaIcons.Fit,
            contentDescription = if (fillScreen) "Adatta l'immagine allo schermo" else "Riempi lo schermo",
            onClick = onToggleFill,
            selected = fillScreen,
            size = 40.dp,
        )
        HudIconButton(
            icon = LunaIcons.Joystick,
            contentDescription = "Comandi del gimbal",
            onClick = onToggleDock,
            selected = dockVisible,
            size = 40.dp,
        )
        HudIconButton(
            icon = LunaIcons.Hide,
            contentDescription = "Nascondi i comandi",
            onClick = onHideChrome,
            size = 40.dp,
        )
    }
}

/** Perché non si vede niente: spenta, in avvio, o ferma con un motivo da leggere. */
private fun previewNote(active: Boolean, framesDecoded: Long, message: String?): String? = when {
    !active -> "Anteprima spenta"
    framesDecoded > 0L -> null
    else -> message ?: "Anteprima in avvio…"
}
