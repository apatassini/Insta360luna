package it.persoft.lunaultra.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Sky = Color(0xFF4FC3F7)
private val SkyDark = Color(0xFF0288D1)
private val Amber = Color(0xFFFFB300)
private val Danger = Color(0xFFE53935)

private val DarkColors = darkColorScheme(
    primary = Sky,
    onPrimary = Color(0xFF00201A),
    secondary = Amber,
    error = Danger,
    background = Color(0xFF0E1626),
    surface = Color(0xFF152134),
)

private val LightColors = lightColorScheme(
    primary = SkyDark,
    secondary = Amber,
    error = Danger,
)

@Composable
fun LunaTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
