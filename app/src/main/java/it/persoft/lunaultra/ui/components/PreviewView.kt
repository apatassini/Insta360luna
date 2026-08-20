package it.persoft.lunaultra.ui.components

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import it.persoft.lunaultra.preview.PreviewSource
import it.persoft.lunaultra.preview.PreviewState
import android.view.Surface

/**
 * Anteprima dal vivo dell'inquadratura.
 *
 * Le due sorgenti hanno bisogno di due contenitori diversi: il MJPEG produce Bitmap da
 * disegnare, il flusso della sessione di controllo produce video che il decoder scrive
 * direttamente su una [Surface]. Non c'è modo di unificarli senza copiare ogni fotogramma,
 * quindi la vista sceglie in base alla sorgente attiva.
 */
@Composable
fun PreviewView(
    state: PreviewState,
    onSurfaceChanged: (Surface?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val frame = state.frame
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when {
            state.usesSurface -> SurfacePreview(onSurfaceChanged)

            frame != null -> Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = "Anteprima dal vivo",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        // Il messaggio resta sopra l'immagine: quando l'anteprima è nera, dice il perché.
        if (frame == null && state.framesDecoded == 0L) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = state.message ?: if (state.active) "Anteprima in avvio…" else "Anteprima spenta",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                if (state.active && state.source != PreviewSource.NESSUNA) {
                    Text(
                        text = "sorgente: ${state.source.name.lowercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun SurfacePreview(onSurfaceChanged: (Surface?) -> Unit) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) {
                        onSurfaceChanged(holder.surface)
                    }

                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        onSurfaceChanged(holder.surface)
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        // Il decoder deve smettere di scrivere prima che la Surface sparisca.
                        onSurfaceChanged(null)
                    }
                })
            }
        },
    )

    DisposableEffect(Unit) {
        onDispose { onSurfaceChanged(null) }
    }
}
