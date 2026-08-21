package it.persoft.lunaultra.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.theme.Luna

/**
 * Superficie di vetro dei comandi sovrapposti all'anteprima.
 *
 * Traslucida e non opaca di proposito: sotto i comandi c'è l'inquadratura, e coprirla del tutto
 * per scrivere due numeri è il modo più veloce di rendere inutile un'anteprima a tutto schermo.
 */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    background: Color = Luna.Glass,
    contentPadding: Dp = 12.dp,
    verticalSpacing: Dp = 8.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(background, shape)
            .border(1.dp, Luna.GlassBorder, shape)
            .padding(contentPadding),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/** Dato di stato in una pastiglia: icona, valore, e un colore che dice se va bene o no. */
@Composable
fun HudPill(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Luna.OnSurface,
    background: Color = Luna.Glass,
    onClick: (() -> Unit)? = null,
) {
    val shape = CircleShape
    Row(
        modifier = modifier
            .background(background, shape)
            .border(1.dp, Luna.GlassBorder, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}

/**
 * Pulsante tondo dei comandi sovrapposti.
 *
 * Quando è attivo si accende del colore dell'accento invece di cambiare icona: il dito copre
 * l'icona nel momento in cui la premi, il colore no.
 */
@Composable
fun HudIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    badge: String? = null,
    activeColor: Color = Luna.Accent,
) {
    val tint = when {
        !enabled -> Luna.OnSurfaceDim.copy(alpha = 0.4f)
        selected -> activeColor
        else -> Luna.OnSurface
    }
    val background = if (selected) activeColor.copy(alpha = 0.18f) else Luna.Glass
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(size)
                .background(background, CircleShape)
                .border(1.dp, if (selected) activeColor.copy(alpha = 0.5f) else Luna.GlassBorder, CircleShape)
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.46f))
        }
        if (badge != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .background(Luna.Accent, CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF00222E),
                )
            }
        }
    }
}

/** Pastiglia della registrazione in corso: il punto lampeggia, come su ogni camera. */
@Composable
fun RecordingPill(label: String, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "rec")
    val blink by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "blink",
    )
    Row(
        modifier = modifier
            .background(Luna.Rec.copy(alpha = 0.22f), CircleShape)
            .border(1.dp, Luna.Rec.copy(alpha = 0.6f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .alpha(blink)
                .background(Luna.Rec, CircleShape),
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** Etichetta minuta sopra l'anteprima, per i dati che non meritano una pastiglia intera. */
@Composable
fun HudCaption(text: String, modifier: Modifier = Modifier, color: Color = Luna.OnSurfaceDim) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier,
    )
}
