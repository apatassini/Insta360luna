package it.persoft.lunaultra.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.theme.Luna
import kotlin.math.hypot
import kotlin.math.min

/**
 * Levetta analogica per il movimento manuale del gimbal.
 *
 * A differenza della croce direzionale muove i due assi insieme e con intensità proporzionale
 * alla distanza dal centro: è il modo in cui si inquadra davvero, perché una panoramica non è
 * mai solo orizzontale e la velocità serve dosarla mentre guardi l'anteprima, non prima.
 *
 * Al centro c'è una zona morta: un dito appoggiato non è un comando, e senza quella soglia il
 * gimbal parte da solo a ogni tocco.
 */
@Composable
fun GimbalJoystick(
    enabled: Boolean,
    onVector: (pan: Float, tilt: Float) -> Unit,
    onRelease: () -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 150.dp,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current

    Box(modifier = modifier.size(diameter)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val radius = min(size.width, size.height) / 2f
                        val center = Offset(size.width / 2f, size.height / 2f)
                        active = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        knob = clampToRadius(down.position - center, radius)
                        emitVector(knob, radius, onVector)
                        do {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change != null && change.pressed) {
                                change.consume()
                                knob = clampToRadius(change.position - center, radius)
                                emitVector(knob, radius, onVector)
                            }
                        } while (change != null && change.pressed)
                        active = false
                        knob = Offset.Zero
                        onRelease()
                    }
                },
        ) {
            val radius = min(size.width, size.height) / 2f
            val center = Offset(size.width / 2f, size.height / 2f)
            val dim = if (enabled) 1f else 0.35f

            drawCircle(color = Luna.Glass.copy(alpha = 0.85f * dim), radius = radius, center = center)
            drawCircle(
                color = Luna.GlassBorder.copy(alpha = 0.9f * dim),
                radius = radius,
                center = center,
                style = Stroke(width = 1.5.dp.toPx()),
            )
            drawCircle(
                color = Luna.OnSurfaceDim.copy(alpha = 0.20f * dim),
                radius = radius * 0.58f,
                center = center,
                style = Stroke(width = 1.dp.toPx()),
            )

            // Croce di riferimento: dice a colpo d'occhio dove sono gli assi puri.
            val tick = radius * 0.16f
            listOf(
                Offset(center.x, center.y - radius) to Offset(center.x, center.y - radius + tick),
                Offset(center.x, center.y + radius) to Offset(center.x, center.y + radius - tick),
                Offset(center.x - radius, center.y) to Offset(center.x - radius + tick, center.y),
                Offset(center.x + radius, center.y) to Offset(center.x + radius - tick, center.y),
            ).forEach { (from, to) ->
                drawLine(
                    color = Luna.OnSurfaceDim.copy(alpha = 0.5f * dim),
                    start = from,
                    end = to,
                    strokeWidth = 2.dp.toPx(),
                )
            }

            val knobCenter = center + knob
            if (knob != Offset.Zero) {
                drawLine(
                    color = Luna.Accent.copy(alpha = 0.5f),
                    start = center,
                    end = knobCenter,
                    strokeWidth = 3.dp.toPx(),
                )
            }
            val knobColor = when {
                !enabled -> Luna.OnSurfaceDim.copy(alpha = 0.35f)
                active -> Luna.Accent
                else -> Color.White.copy(alpha = 0.85f)
            }
            drawCircle(color = knobColor.copy(alpha = knobColor.alpha * 0.25f), radius = radius * 0.34f, center = knobCenter)
            drawCircle(color = knobColor, radius = radius * 0.26f, center = knobCenter)
        }
    }
}

private fun clampToRadius(offset: Offset, radius: Float): Offset {
    if (radius <= 0f) return Offset.Zero
    val magnitude = hypot(offset.x, offset.y)
    if (magnitude <= radius) return offset
    val scale = radius / magnitude
    return Offset(offset.x * scale, offset.y * scale)
}

/** Zona morta: sotto questa frazione del raggio il comando vale zero. */
private const val DEAD_ZONE = 0.12f

private fun emitVector(knob: Offset, radius: Float, onVector: (Float, Float) -> Unit) {
    if (radius <= 0f) {
        onVector(0f, 0f)
        return
    }
    val x = (knob.x / radius).coerceIn(-1f, 1f)
    val y = (knob.y / radius).coerceIn(-1f, 1f)
    if (hypot(x, y) < DEAD_ZONE) {
        onVector(0f, 0f)
    } else {
        // Sullo schermo l'asse Y cresce verso il basso, il tilt verso l'alto: va invertito.
        onVector(x, -y)
    }
}
