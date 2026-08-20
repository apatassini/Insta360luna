package it.persoft.lunaultra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * Croce direzionale con comportamento "premi e tieni premuto": il gimbal si muove finché
 * il dito resta sul tasto e si ferma al rilascio, come nell'app ufficiale.
 */
@Composable
fun GimbalPad(
    enabled: Boolean,
    onJog: (pan: Float, tilt: Float) -> Unit,
    onRelease: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HoldKey(Icons.Filled.KeyboardArrowUp, "Tilt su", enabled, { onJog(0f, 1f) }, onRelease)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HoldKey(Icons.Filled.KeyboardArrowLeft, "Pan sinistra", enabled, { onJog(-1f, 0f) }, onRelease)
            TapKey(Icons.Filled.Close, "Stop movimento", enabled, onStop)
            HoldKey(Icons.Filled.KeyboardArrowRight, "Pan destra", enabled, { onJog(1f, 0f) }, onRelease)
        }
        HoldKey(Icons.Filled.KeyboardArrowDown, "Tilt giù", enabled, { onJog(0f, -1f) }, onRelease)
    }
}

@Composable
private fun HoldKey(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .background(
                color = if (enabled) colors.primaryContainer else colors.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        onPress()
                        tryAwaitRelease()
                        onRelease()
                    }
                )
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )
    }
}

@Composable
private fun TapKey(
    icon: ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(72.dp)
            .background(
                color = if (enabled) colors.errorContainer else colors.surfaceVariant,
                shape = RoundedCornerShape(16.dp),
            )
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onTap = { onClick() })
            },
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (enabled) colors.onErrorContainer else colors.onSurfaceVariant,
        )
    }
}
