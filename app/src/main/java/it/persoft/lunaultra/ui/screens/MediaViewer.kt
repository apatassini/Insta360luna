package it.persoft.lunaultra.ui.screens

import android.app.Activity
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import it.persoft.lunaultra.ui.ViewerState
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.media.SphereImage
import it.persoft.lunaultra.ui.media.SphereState
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Quanto largo è il bordo che, toccato, cambia file. */
private const val EDGE_FRACTION = 0.22f

/** Quanto si deve trascinare, in frazione di schermo, perché il file cambi. */
private const val SWIPE_FRACTION = 0.22f

/** Dopo quanto spariscono i comandi se non li tocchi. */
private const val CHROME_TIMEOUT_MS = 2_600L

/**
 * Un file a schermo intero.
 *
 * Guardare una foto vuol dire guardare la foto: le barre di sistema spariscono, i comandi si
 * tolgono da soli dopo un paio di secondi e tornano al tocco. Restano tre modi di cambiare
 * immagine perché servono in momenti diversi — le frecce quando si guarda con calma, il bordo
 * dello schermo quando si scorre veloce con il pollice, il trascinamento quando si sfoglia.
 *
 * Le panoramiche non si mostrano piatte: un'equirettangolare stesa deforma i poli e taglia in
 * due la scena dove i bordi si ricongiungono. Si aprono dentro la sfera, e ci si gira intorno.
 */
@Composable
fun MediaViewer(
    state: ViewerState,
    favorite: Boolean,
    onClose: () -> Unit,
    onStep: (Int) -> Unit,
    onDownload: () -> Unit,
    onToggleFavorite: () -> Unit,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val item = state.item ?: return
    BackHandler(enabled = true, onBack = onClose)
    ImmersiveWhileOpen()

    var chromeVisible by remember { mutableStateOf(true) }
    // Il video parte da solo e si comanda col tocco: al centro pausa/riparte, ai lati si
    // cambia file. Il pulsante nero in mezzo alla scena era l'unica cosa che si vedeva.
    var videoPlaying by remember(item.path) { mutableStateOf(true) }
    var scale by remember(item.path) { mutableFloatStateOf(1f) }
    var offset by remember(item.path) { mutableStateOf(Offset.Zero) }
    val sphere = remember(item.path) { SphereState() }
    // Il visore navigabile si sceglie, non si subisce: anche una panoramica 2:1 che copre un
    // pezzo di sfera finiva dentro la palla, e una striscia di paesaggio dentro una sfera è
    // quasi tutta nera. La foto si apre piatta; il pulsante della sfera resta lì per chi la
    // vuole girare.
    var sphereMode by remember(item.path) { mutableStateOf(false) }
    val sphereAvailable = item.panoramic || state.photo?.let(::looksEquirectangular) == true

    LaunchedEffect(item.path, chromeVisible) {
        if (chromeVisible) {
            delay(CHROME_TIMEOUT_MS)
            chromeVisible = false
        }
    }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(item.path, sphereMode) {
                val width = size.width.toFloat().coerceAtLeast(1f)
                val slop = viewConfiguration.touchSlop
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var pan = Offset.Zero
                    var moved = false
                    var pressed = true
                    while (pressed) {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val panChange = event.calculatePan()
                        if (zoom != 1f) {
                            moved = true
                            if (sphereMode) sphere.zoomBy(zoom) else scale = (scale * zoom).coerceIn(1f, 8f)
                        }
                        if (panChange != Offset.Zero) {
                            pan += panChange
                            if (abs(pan.x) > slop || abs(pan.y) > slop) moved = true
                            if (sphereMode) {
                                // Un dito che attraversa lo schermo gira di quanto si vede:
                                // così la scena segue la mano invece di scappare.
                                val degreesPerPixel = sphere.fovDegrees / width
                                // Il dito trascina la scena, non la testa: destra e sinistra
                                // seguono il gesto, come in ogni visore di sferiche.
                                sphere.rotateBy(panChange.x * degreesPerPixel, panChange.y * degreesPerPixel)
                            } else if (scale > 1f) {
                                offset += panChange
                            }
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        pressed = event.changes.any { it.pressed }
                    }
                    when {
                        !moved -> when {
                            down.position.x < width * EDGE_FRACTION -> onStep(-1)
                            down.position.x > width * (1f - EDGE_FRACTION) -> onStep(1)
                            item.isVideo -> videoPlaying = !videoPlaying
                            else -> chromeVisible = !chromeVisible
                        }

                        !sphereMode && scale <= 1f &&
                            abs(pan.x) > width * SWIPE_FRACTION && abs(pan.x) > abs(pan.y) ->
                            onStep(if (pan.x < 0f) 1 else -1)
                    }
                    if (!sphereMode && scale <= 1f) offset = Offset.Zero
                }
            },
    ) {
        val photo = state.photo
        when {
            photo != null && sphereMode -> SphereImage(
                bitmap = photo,
                state = sphere,
                modifier = Modifier.fillMaxSize(),
            )

            photo != null -> Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )

            state.videoFile != null -> VideoPlayer(path = state.videoFile, playing = videoPlaying)

            state.loading -> Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(color = Luna.Accent)
                Text(
                    text = if (state.progress > 0f) "Scaricamento ${(state.progress * 100).toInt()}%"
                    else "Scaricamento dalla camera…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier.padding(top = 14.dp),
                )
            }

            else -> Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = LunaIcons.Warning,
                    contentDescription = null,
                    tint = Luna.Warn,
                    modifier = Modifier.size(40.dp),
                )
                Text(
                    text = state.message ?: "Non visualizzabile",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }

        if (state.loading && state.progress > 0f) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
            )
        }

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .background(Luna.ScrimStrong)
                        .safeDrawingPadding()
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    HudIconButton(
                        icon = LunaIcons.Close,
                        contentDescription = "Chiudi",
                        onClick = onClose,
                        size = 40.dp,
                    )
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    if (sphereAvailable && state.photo != null) {
                        HudIconButton(
                            icon = LunaIcons.Panorama,
                            contentDescription = if (sphereMode) "Mostra piatta" else "Mostra a 360°",
                            onClick = {
                                sphereMode = !sphereMode
                                sphere.reset()
                                scale = 1f
                                offset = Offset.Zero
                            },
                            size = 40.dp,
                            selected = sphereMode,
                            activeColor = Luna.Pano,
                        )
                    }
                    HudIconButton(
                        icon = if (favorite) LunaIcons.Star else LunaIcons.StarOutline,
                        contentDescription = if (favorite) "Togli dai preferiti" else "Aggiungi ai preferiti",
                        onClick = onToggleFavorite,
                        size = 40.dp,
                        selected = favorite,
                        activeColor = Luna.Photo,
                    )
                    HudIconButton(
                        icon = LunaIcons.Download,
                        contentDescription = "Salva nella galleria del telefono",
                        onClick = onDownload,
                        size = 40.dp,
                        activeColor = Luna.Pano,
                    )
                }

                // Niente frecce sopra la foto: si cambia toccando i bordi laterali, che ci
                // sono già e non coprono niente.
                if (total > 0 && state.index >= 0) {
                    Text(
                        text = "${state.index + 1} / $total",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .safeDrawingPadding()
                            .padding(bottom = 14.dp)
                            .background(Luna.Glass, CircleShape)
                            .padding(horizontal = 12.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

private fun looksEquirectangular(bitmap: android.graphics.Bitmap): Boolean =
    bitmap.height > 0 && bitmap.width.toFloat() / bitmap.height.toFloat() in 1.85f..2.15f

/**
 * Toglie le barre di sistema finché il visore è aperto.
 *
 * A schermo intero vuol dire davvero intero: ruotando il telefono la foto prende tutto, senza
 * l'orologio sopra e i tasti sotto. Alla chiusura tornano com'erano.
 */
@Composable
private fun ImmersiveWhileOpen() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun VideoPlayer(path: String, playing: Boolean) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(path)
                    setOnPreparedListener { player ->
                        player.isLooping = true
                        start()
                    }
                }
            },
            update = { view ->
                if (playing && !view.isPlaying) view.start()
                if (!playing && view.isPlaying) view.pause()
            },
        )
    }
}
