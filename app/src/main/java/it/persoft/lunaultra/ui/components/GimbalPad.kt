package it.persoft.lunaultra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons

/**
 * Croce direzionale: un asse alla volta, a velocità costante.
 *
 * Convive con la levetta analogica perché serve a un'altra cosa. La levetta è per inquadrare a
 * mano libera; la croce è per correggere un punto memorizzato senza spostare l'altro asse per
 * sbaglio — e con il dito su un tasto grande si guarda l'anteprima, non il comando.
 */
@Composable
fun GimbalPad(
    enabled: Boolean,
    onJog: (pan: Float, tilt: Float) -> Unit,
    onRelease: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    keySize: Dp = 54.dp,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HoldKey(LunaIcons.Up, "Inclina verso l'alto", enabled, keySize, { onJog(0f, 1f) }, onRelease)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HoldKey(LunaIcons.Left, "Ruota a sinistra", enabled, keySize, { onJog(-1f, 0f) }, onRelease)
            StopKey(enabled, keySize, onStop)
            HoldKey(LunaIcons.Right, "Ruota a destra", enabled, keySize, { onJog(1f, 0f) }, onRelease)
        }
        HoldKey(LunaIcons.Down, "Inclina verso il basso", enabled, keySize, { onJog(0f, -1f) }, onRelease)
    }
}

@Composable
private fun HoldKey(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    keySize: Dp,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    var pressed by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(keySize)
            .background(
                color = when {
                    !enabled -> Luna.GlassSoft
                    pressed -> Luna.Accent.copy(alpha = 0.30f)
                    else -> Luna.Glass
                },
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = if (pressed && enabled) Luna.Accent.copy(alpha = 0.7f) else Luna.GlassBorder,
                shape = shape,
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        pressed = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onPress()
                        tryAwaitRelease()
                        pressed = false
                        onRelease()
                    },
                )
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = when {
                !enabled -> Luna.OnSurfaceDim.copy(alpha = 0.4f)
                pressed -> Luna.Accent
                else -> Color.White
            },
            modifier = Modifier.size(keySize * 0.5f),
        )
    }
}

@Composable
private fun StopKey(enabled: Boolean, keySize: Dp, onStop: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(keySize)
            .background(
                color = if (enabled) Luna.Rec.copy(alpha = 0.22f) else Luna.GlassSoft,
                shape = CircleShape,
            )
            .border(
                width = 1.dp,
                color = if (enabled) Luna.Rec.copy(alpha = 0.6f) else Luna.GlassBorder,
                shape = CircleShape,
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onStop() })
            },
    ) {
        Icon(
            imageVector = LunaIcons.Stop,
            contentDescription = "Ferma il movimento",
            tint = if (enabled) Luna.Rec else Luna.OnSurfaceDim.copy(alpha = 0.4f),
            modifier = Modifier.size(keySize * 0.42f),
        )
    }
}
