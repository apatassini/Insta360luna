package it.persoft.lunaultra.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Palette dell'app.
 *
 * Un'app che sta sopra un'anteprima dal vivo non può avere un tema chiaro: qualsiasi superficie
 * luminosa attorno all'immagine falsa la lettura dell'esposizione e, di sera, acceca. Per questo
 * il tema è scuro sempre, anche a sistema chiaro — come fanno tutte le app di ripresa.
 */
object Luna {
    /** Azzurro dell'accento: leggibile sopra il nero, distinguibile dal rosso della registrazione. */
    val Accent = Color(0xFF52D3FF)
    val AccentDim = Color(0xFF1B5D75)
    val Amber = Color(0xFFFFC24B)

    /** Rosso della registrazione, usato solo per quello: se compare, la camera sta girando. */
    val Rec = Color(0xFFFF4D4F)
    val Ok = Color(0xFF3DDC97)
    val Warn = Color(0xFFFFB020)

    val Bg = Color(0xFF07090D)
    val Surface = Color(0xFF121821)
    val SurfaceHigh = Color(0xFF1B2430)
    val OnSurface = Color(0xFFE7EDF5)
    val OnSurfaceDim = Color(0xFF97A5B8)

    /** Vetro dei comandi sovrapposti all'anteprima: scuro e traslucido, mai opaco. */
    val Glass = Color(0xB3070A0F)
    val GlassSoft = Color(0x80070A0F)
    val GlassBorder = Color(0x1FFFFFFF)

    /** Sfumatura che stacca l'anteprima dai comandi in alto e in basso. */
    val ScrimStrong = Color(0xCC000000)
    val ScrimNone = Color(0x00000000)

    /** Fasce dei comandi sopra e sotto l'anteprima: piene, non trasparenti. */
    val Band = Color(0xFF0A0D12)

    /**
     * I colori delle modalità.
     *
     * Ogni modalità ne ha uno e lo porta ovunque: la voce accesa nella ghiera, l'anello del
     * pulsante di scatto, il distintivo in alto. Il colore è ciò che si riconosce con la coda
     * dell'occhio mentre si guarda l'inquadratura — una scritta va letta, un colore no.
     */
    val Photo = Color(0xFFFFD54F)
    val Movie = Color(0xFFFF5A5F)
    val Lapse = Color(0xFFFFA033)
    val Path = Color(0xFF4FD1FF)
    val PathLapse = Color(0xFFB388FF)
    val Pano = Color(0xFF5CE6A5)
}

private val LunaColors = darkColorScheme(
    primary = Luna.Accent,
    onPrimary = Color(0xFF00222E),
    primaryContainer = Luna.AccentDim,
    onPrimaryContainer = Color(0xFFD5F4FF),
    secondary = Luna.Amber,
    onSecondary = Color(0xFF2A1C00),
    secondaryContainer = Color(0xFF54430F),
    onSecondaryContainer = Color(0xFFFFE7B0),
    tertiary = Luna.Ok,
    onTertiary = Color(0xFF00281A),
    error = Luna.Rec,
    onError = Color(0xFF3A0004),
    errorContainer = Color(0xFF6B1417),
    onErrorContainer = Color(0xFFFFD9D9),
    background = Luna.Bg,
    onBackground = Luna.OnSurface,
    surface = Luna.Surface,
    onSurface = Luna.OnSurface,
    surfaceVariant = Luna.SurfaceHigh,
    onSurfaceVariant = Luna.OnSurfaceDim,
    surfaceContainer = Luna.Surface,
    surfaceContainerHigh = Luna.SurfaceHigh,
    surfaceContainerHighest = Color(0xFF232E3D),
    outline = Color(0xFF3A4757),
    outlineVariant = Color(0xFF27303C),
)

private val LunaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val LunaTypography: Typography = Typography().let { base ->
    base.copy(
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.3.sp),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
        labelSmall = base.labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.5.sp),
    )
}

@Composable
fun LunaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = LunaColors,
        shapes = LunaShapes,
        typography = LunaTypography,
        content = content,
    )
}
