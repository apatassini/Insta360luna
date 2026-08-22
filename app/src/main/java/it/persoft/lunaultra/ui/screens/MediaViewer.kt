package it.persoft.lunaultra.ui.screens

import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
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
import androidx.compose.runtime.DisposableEffect
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

    Box(modifier = modifier.background(Color.Black)) {
        when {
            state.photo != null -> ZoomableImage(state)
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
private fun ZoomableImage(state: ViewerState) {
    val bitmap = state.photo ?: return
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = state.item?.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    // A scala 1 l'immagine torna centrata: uno spostamento residuo su una foto
                    // che riempie lo schermo si legge come un difetto.
                    offset = if (scale <= 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
    )
}

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

    DisposableEffect(path) {
        onDispose { }
    }
}
