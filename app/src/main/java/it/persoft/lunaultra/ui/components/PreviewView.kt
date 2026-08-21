package it.persoft.lunaultra.ui.components

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView
import it.persoft.lunaultra.preview.PreviewState

/**
 * L'anteprima dal vivo, a tutto schermo.
 *
 * Due sorgenti, due contenitori: il MJPEG produce Bitmap da disegnare, lo stream della sessione
 * di controllo produce video che il decoder scrive su una [Surface]. Non si unificano senza
 * copiare ogni fotogramma, quindi la vista sceglie in base alla sorgente attiva.
 *
 * Il riquadro dell'immagine viene calcolato a mano invece di usare `aspectRatio`: per riempire
 * lo schermo l'immagine deve poter debordare e farsi tagliare, e un modificatore che rispetta i
 * vincoli in arrivo non deborda mai — ripiega sull'adattamento, che è l'opposto di quel che serve.
 */
@Composable
fun PreviewSurface(
    state: PreviewState,
    onSurfaceChanged: (Surface?) -> Unit,
    modifier: Modifier = Modifier,
    fillScreen: Boolean = false,
) {
    val frame = state.frame
    val sourceRatio = when {
        frame != null && frame.height > 0 -> frame.width.toFloat() / frame.height.toFloat()
        else -> 16f / 9f
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .clipToBounds(),
        contentAlignment = Alignment.Center,
    ) {
        val containerRatio = if (maxHeight.value > 0f) maxWidth / maxHeight else sourceRatio
        // Riempire vuol dire tagliare il lato che avanza; adattare vuol dire lasciare le bande.
        val widthDrivesSize = if (fillScreen) containerRatio < sourceRatio else containerRatio > sourceRatio
        val boxWidth = if (widthDrivesSize) maxHeight * sourceRatio else maxWidth
        val boxHeight = if (widthDrivesSize) maxHeight else maxWidth / sourceRatio

        Box(modifier = Modifier.size(boxWidth, boxHeight), contentAlignment = Alignment.Center) {
            when {
                state.usesSurface -> SurfacePreview(onSurfaceChanged)

                frame != null -> Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = "Anteprima dal vivo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
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
