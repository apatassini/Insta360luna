package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.components.GimbalJoystick
import it.persoft.lunaultra.ui.components.GlassPanel
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.components.SliderRow
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * Controllo gimbal da mirino: la levetta resta piccola nell'angolo in basso a destra e non
 * copre l'inquadratura. I tre puntini aprono solo quando serve il pannello con velocità,
 * memorizzazione del punto, azzeramento e stop.
 */
@Composable
fun GimbalDock(
    enabled: Boolean,
    moving: Boolean,
    panDegrees: Float,
    tiltDegrees: Float,
    positionFromCamera: Boolean,
    speedPercent: Int,
    hardwareSpeedLevel: Int,
    onSpeedChange: (Int) -> Unit,
    onHardwareSpeedChange: (Int) -> Unit,
    onVector: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    onStop: () -> Unit,
    onZero: () -> Unit,
    onCaptureWaypoint: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var optionsVisible by remember { mutableStateOf(false) }
    val joystickSize = if (compact) 76.dp else 88.dp
    val optionsWidth = if (compact) 214.dp else 236.dp
    val totalHeight = if (optionsVisible) (if (compact) 342.dp else 360.dp) else joystickSize + 38.dp

    Box(
        modifier = modifier
            .width(optionsWidth)
            .height(totalHeight),
    ) {
        if (optionsVisible) {
            GimbalOptions(
                enabled = enabled,
                moving = moving,
                panDegrees = panDegrees,
                tiltDegrees = tiltDegrees,
                positionFromCamera = positionFromCamera,
                speedPercent = speedPercent,
                hardwareSpeedLevel = hardwareSpeedLevel,
                onSpeedChange = onSpeedChange,
                onHardwareSpeedChange = onHardwareSpeedChange,
                onStop = onStop,
                onZero = onZero,
                onCaptureWaypoint = onCaptureWaypoint,
                modifier = Modifier.align(Alignment.TopEnd).width(optionsWidth),
            )
        }

        Column(
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            GimbalJoystick(
                enabled = enabled,
                onVector = onVector,
                onRelease = onRelease,
                diameter = joystickSize,
            )
            HudIconButton(
                icon = LunaIcons.More,
                contentDescription = if (optionsVisible) "Chiudi opzioni gimbal" else "Apri opzioni gimbal",
                onClick = { optionsVisible = !optionsVisible },
                selected = optionsVisible,
                size = 32.dp,
            )
        }
    }
}

@Composable
private fun GimbalOptions(
    enabled: Boolean,
    moving: Boolean,
    panDegrees: Float,
    tiltDegrees: Float,
    positionFromCamera: Boolean,
    speedPercent: Int,
    hardwareSpeedLevel: Int,
    onSpeedChange: (Int) -> Unit,
    onHardwareSpeedChange: (Int) -> Unit,
    onStop: () -> Unit,
    onZero: () -> Unit,
    onCaptureWaypoint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        modifier = modifier.pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentPadding = 10.dp,
        verticalSpacing = 7.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        when {
                            moving -> Luna.Accent
                            enabled -> Luna.Ok
                            else -> Luna.OnSurfaceDim
                        },
                        CircleShape,
                    ),
            )
            Text("OPZIONI GIMBAL", style = MaterialTheme.typography.labelMedium, color = Color.White)
        }

        SliderRow(
            label = "Intensità joystick",
            value = speedPercent.toFloat(),
            onValueChange = { onSpeedChange(it.roundToInt()) },
            valueRange = 1f..100f,
            valueLabel = "$speedPercent%",
            icon = LunaIcons.Speed,
            enabled = enabled,
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Preset", style = MaterialTheme.typography.labelSmall, color = Luna.OnSurfaceDim)
            listOf(1 to "25", 2 to "50", 3 to "75").forEach { (level, label) ->
                FilterChip(
                    selected = hardwareSpeedLevel == level,
                    onClick = { onHardwareSpeedChange(level) },
                    enabled = enabled,
                    label = { Text(label) },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            HudIconButton(
                icon = LunaIcons.Center,
                contentDescription = "Considera questa posizione come 0° / 0°",
                onClick = onZero,
                enabled = enabled,
                size = 38.dp,
            )
            HudIconButton(
                icon = LunaIcons.Waypoint,
                contentDescription = "Memorizza punto e inquadratura",
                onClick = onCaptureWaypoint,
                enabled = enabled,
                size = 38.dp,
                activeColor = Luna.Path,
            )
            HudIconButton(
                icon = LunaIcons.Stop,
                contentDescription = "Ferma il gimbal",
                onClick = onStop,
                enabled = enabled,
                selected = moving,
                size = 38.dp,
                activeColor = Luna.Rec,
            )
        }

        Text(
            text = "pan %.1f° · tilt %.1f°%s".format(
                panDegrees,
                tiltDegrees,
                if (positionFromCamera) "" else " (stima)",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = Luna.OnSurfaceDim,
        )
    }
}
