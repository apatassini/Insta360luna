package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.timelapse.LunaOptics
import it.persoft.lunaultra.ui.theme.Luna

/**
 * Selettore ottico sempre raggiungibile dal mirino.
 *
 * Lo zoom non è una regolazione esclusivamente fotografica: il protocollo lo memorizza nel
 * function mode attivo. Tenerlo dentro il solo pannello Foto lo faceva sparire proprio durante
 * Video, PureVideo e Slow-motion.
 */
@Composable
fun ZoomDock(
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .background(Luna.Glass, RoundedCornerShape(24.dp))
            .border(1.dp, Luna.GlassBorder, RoundedCornerShape(24.dp))
            .padding(horizontal = 5.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LunaOptics.zoomStops.forEach { zoom ->
            val active = zoom == selected
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .background(
                        color = if (active) Luna.Accent else Color.Transparent,
                        shape = CircleShape,
                    )
                    .clickable(enabled = enabled && !active) { onSelect(zoom) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${zoom}×",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    color = when {
                        active -> Color(0xFF00222E)
                        enabled -> Color.White
                        else -> Luna.OnSurfaceDim.copy(alpha = 0.45f)
                    },
                )
            }
        }
    }
}
