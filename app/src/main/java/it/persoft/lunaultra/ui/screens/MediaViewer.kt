package it.persoft.lunaultra.ui.screens

import android.widget.VideoView
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.activity.compose.BackHandler
import it.persoft.lunaultra.ui.ViewerState
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons

/**
 * Un file a schermo intero.
 *
 * Le foto si guardano ridotte a quanto serve allo schermo e si ingrandiscono con due dita; i
 * video si riproducono dalla copia locale, non in streaming dalla camera — il lettore di sistema
 * apre connessioni sue, che non passano dal binding sulla rete della camera e finirebbero sui
 * dati mobili a cercare un indirizzo che lì non esiste.
 */
@Composable
fun MediaViewer(
    state: ViewerState,
    onClose: () -> Unit,
    onStep: (Int) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = state.item ?: return
    BackHandler(enabled = true, onBack = onClose)

    // Lo zoom vive qui e non dentro l'immagine perché lo stesso gesto fa due cose: a scala 1
    // uno scorrimento orizzontale cambia file, da lì in su trascina l'immagine ingrandita.
    var scale by remember(item.path) { mutableFloatStateOf(1f) }
    var offset by remember(item.path) { mutableStateOf(Offset.Zero) }

    Box(
        modifier = modifier
            .background(Color.Black)
            .pointerInput(item.path) {
                val swipeThreshold = SWIPE_THRESHOLD.toPx()
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var accumulated = Offset.Zero
                    var stepped = false
                    var pressed: Boolean
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f) scale = (scale * zoom).coerceIn(1f, 8f)
                        if (scale > 1f) {
                            offset += pan
                        } else {
                            accumulated += pan
                            // Il cambio scatta appena la soglia è superata, non al rilascio:
                            // sfogliare deve rispondere sotto il dito.
                            if (!stepped && kotlin.math.abs(accumulated.x) > swipeThreshold &&
                                kotlin.math.abs(accumulated.x) > kotlin.math.abs(accumulated.y)
                            ) {
                                stepped = true
                                onStep(if (accumulated.x < 0f) 1 else -1)
                            }
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                        pressed = event.changes.any { it.pressed }
                    } while (pressed)
                    if (scale <= 1f) offset = Offset.Zero
                }
            },
    ) {
        when {
            state.photo != null -> ZoomableImage(state, scale, offset)
            state.videoFile != null -> VideoPlayer(path = state.videoFile)
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

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Luna.ScrimStrong)
                .safeDrawingPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            HudIconButton(
                icon = LunaIcons.Close,
                contentDescription = "Chiudi",
                onClick = onClose,
                size = 40.dp,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(mediaDateLabel(item.takenAtMs))
                        if (state.message != null && state.videoFile != null) append("  ·  ${state.message}")
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Luna.OnSurfaceDim,
                    maxLines = 1,
                )
            }
            HudIconButton(
                icon = LunaIcons.Download,
                contentDescription = "Salva nella galleria del telefono",
                onClick = onDownload,
                size = 40.dp,
                activeColor = Luna.Pano,
            )
        }

        if (state.loading && state.progress > 0f) {
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            HudIconButton(
                icon = LunaIcons.Left,
                contentDescription = "File precedente",
                onClick = { onStep(-1) },
                size = 44.dp,
            )
            HudIconButton(
                icon = LunaIcons.Right,
                contentDescription = "File successivo",
                onClick = { onStep(1) },
                size = 44.dp,
            )
        }
    }
}

@Composable
private fun ZoomableImage(state: ViewerState, scale: Float, offset: Offset) {
    val bitmap = state.photo ?: return
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = state.item?.name,
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
}

/** Quanto si deve trascinare, di lato, perché il file cambi. */
private val SWIPE_THRESHOLD = 96.dp

@Composable
private fun VideoPlayer(path: String) {
    var playing by remember { mutableStateOf(true) }
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
        HudIconButton(
            icon = if (playing) LunaIcons.Stop else LunaIcons.Play,
            contentDescription = if (playing) "Pausa" else "Riproduci",
            onClick = { playing = !playing },
            size = 56.dp,
        )
    }

}
