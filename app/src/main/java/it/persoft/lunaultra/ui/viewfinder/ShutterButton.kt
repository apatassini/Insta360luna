package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Il pulsante di scatto.
 *
 * Cambia forma con la modalità perché è l'unico comando che si preme senza guardarlo: bianco e
 * tondo si scatta, rosso e tondo si registra, quadrato si sta registrando e quel tocco ferma.
 * Nelle modalità guidate l'anello porta anche l'avanzamento della sequenza, che è l'informazione
 * che si cerca mentre gira.
 */
@Composable
fun ShutterButton(
    mode: CaptureMode,
    active: Boolean,
    progress: Float,
    ready: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 78.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(targetValue = if (pressed) 0.92f else 1f, label = "shutter")

    // Il pieno del pulsante porta il colore della modalità: è il modo più rapido di sapere cosa
    // succede premendolo, senza leggere la ghiera.
    val accent = mode.color
    val description = when {
        active -> "Ferma"
        mode == CaptureMode.FOTO -> "Scatta"
        mode.usesSequence -> "Avvia la sequenza"
        else -> "Avvia la registrazione"
    }

    Box(
        modifier = modifier
            .size(diameter)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (ready) 1f else 0.45f
            }
            // Premibile anche quando non è pronto: un pulsante che non fa niente e non dice
            // niente lascia l'utente a indovinare quale delle tre condizioni manca.
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .semantics { contentDescription = description },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val ring = 4.dp.toPx()
            val radius = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawCircle(color = Color.Black.copy(alpha = 0.35f), radius = radius, center = center)
            drawCircle(
                color = Color.White.copy(alpha = 0.9f),
                radius = radius - ring / 2f,
                center = center,
                style = Stroke(width = ring),
            )

            if (progress > 0f) {
                drawArc(
                    color = mode.color,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(ring / 2f, ring / 2f),
                    size = Size(size.width - ring, size.height - ring),
                    style = Stroke(width = ring),
                )
            }

            val innerRadius = radius * 0.72f
            if (active) {
                val side = radius * 0.86f
                drawRoundRect(
                    color = mode.color,
                    topLeft = Offset(center.x - side / 2f, center.y - side / 2f),
                    size = Size(side, side),
                    cornerRadius = CornerRadius(side * 0.22f, side * 0.22f),
                )
            } else {
                drawCircle(color = accent, radius = innerRadius, center = center)
            }
        }
    }
}
