package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import it.persoft.lunaultra.ui.components.GimbalPad
import it.persoft.lunaultra.ui.components.GlassPanel
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.components.SliderRow
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * Il pannello del gimbal manuale, sovrapposto all'anteprima.
 *
 * Due comandi per lo stesso movimento, che non è una ripetizione: la levetta serve a inquadrare
 * a mano libera muovendo i due assi insieme, la croce a correggere un asse alla volta senza
 * toccare l'altro. Il cursore della velocità agisce mentre il gimbal si muove.
 *
 * Finché il numero del comando gimbal non è noto il pannello resta visibile ma inerte, e lo
 * dice: nasconderlo farebbe sembrare l'app incompleta invece che in attesa di un dato.
 */
@Composable
fun GimbalDock(
    enabled: Boolean,
    codeKnown: Boolean,
    moving: Boolean,
    panDegrees: Float,
    tiltDegrees: Float,
    positionFromCamera: Boolean,
    speedPercent: Int,
    onSpeedChange: (Int) -> Unit,
    onVector: (Float, Float) -> Unit,
    onJog: (Float, Float) -> Unit,
    onRelease: () -> Unit,
    onStop: () -> Unit,
    onZero: () -> Unit,
    onCaptureWaypoint: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    var usePad by remember { mutableStateOf(false) }
    val active = enabled && codeKnown

    GlassPanel(
        // In orizzontale l'altezza è poca e il pannello va rimpicciolito, non troncato: una
        // levetta tagliata a metà dal bordo dello schermo non si usa.
        modifier = modifier
            .width(if (compact) 214.dp else 252.dp)
            .pointerInput(Unit) { detectTapGestures(onTap = {}) },
        contentPadding = 10.dp,
        verticalSpacing = 8.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(
                        color = when {
                            !codeKnown -> Luna.Warn
                            moving -> Luna.Accent
                            active -> Luna.Ok
                            else -> Luna.OnSurfaceDim
                        },
                        shape = CircleShape,
                    ),
            )
            Text(
                text = "GIMBAL",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            SmallToggle(
                icon = LunaIcons.Joystick,
                description = "Levetta analogica",
                selected = !usePad,
                onClick = { usePad = false },
            )
            SmallToggle(
                icon = LunaIcons.DPad,
                description = "Croce direzionale",
                selected = usePad,
                onClick = { usePad = true },
            )
            SmallToggle(
                icon = LunaIcons.Close,
                description = "Chiudi il pannello del gimbal",
                selected = false,
                onClick = onClose,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (usePad) {
                GimbalPad(
                    enabled = active,
                    onJog = onJog,
                    onRelease = onRelease,
                    onStop = onStop,
                    keySize = if (compact) 42.dp else 48.dp,
                )
            } else {
                GimbalJoystick(
                    enabled = active,
                    onVector = onVector,
                    onRelease = onRelease,
                    diameter = if (compact) 122.dp else 152.dp,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HudIconButton(
                    icon = LunaIcons.Center,
                    contentDescription = "Considera questa posizione come 0° / 0°",
                    onClick = onZero,
                    size = 40.dp,
                )
                HudIconButton(
                    icon = LunaIcons.Waypoint,
                    contentDescription = "Memorizza il punto corrente",
                    onClick = onCaptureWaypoint,
                    size = 40.dp,
                )
                HudIconButton(
                    icon = LunaIcons.Stop,
                    contentDescription = "Ferma il movimento",
                    onClick = onStop,
                    size = 40.dp,
                    selected = moving,
                    activeColor = Luna.Rec,
                )
            }
        }

        SliderRow(
            label = "Velocità",
            value = speedPercent.toFloat(),
            onValueChange = { onSpeedChange(it.roundToInt()) },
            valueRange = 1f..100f,
            valueLabel = "$speedPercent%",
            icon = LunaIcons.Speed,
            enabled = active,
        )

        Text(
            text = "pan %.1f°  ·  tilt %.1f°%s".format(
                panDegrees,
                tiltDegrees,
                if (positionFromCamera) "" else "  (stima)",
            ),
            style = MaterialTheme.typography.labelSmall,
            color = Luna.OnSurfaceDim,
        )

        if (!codeKnown) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Luna.Warn.copy(alpha = 0.14f), RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenDiagnostics)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = LunaIcons.Warning,
                    contentDescription = null,
                    tint = Luna.Warn,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Comando gimbal non ancora noto: cercalo in Diagnostica",
                    style = MaterialTheme.typography.labelSmall,
                    color = Luna.Warn,
                )
            }
        }
    }
}

@Composable
private fun SmallToggle(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(30.dp)
            .background(
                color = if (selected) Luna.Accent.copy(alpha = 0.22f) else Color.Transparent,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (selected) Luna.Accent else Luna.OnSurfaceDim,
            modifier = Modifier.size(17.dp),
        )
    }
}
