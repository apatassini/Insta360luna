package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons

/** Altezza della fascia inferiore, esclusa la barra di sistema. */
val BottomBandHeight = 96.dp

/** Larghezza della fascia dei comandi quando passa di lato, in orizzontale. */
val SideBandWidth = 124.dp

/**
 * La fascia di scatto: ghiera delle modalità e i quattro comandi che le stanno attorno.
 *
 * L'impaginazione è quella di una camera perché è quella che il pollice conosce già: memorizza
 * punto a sinistra, scatto al centro, regolazioni a destra. La ghiera delle modalità non è più
 * qui: occupava una fascia intera per una scelta che si fa una volta ogni tanto, mentre
 * l'inquadratura è ciò che si guarda sempre. Adesso si cambia modalità dal distintivo in cima.
 */
@Composable
fun CaptureBar(
    selected: CaptureMode,
    active: Boolean,
    progress: Float,
    shutterReady: Boolean,
    onShutter: () -> Unit,
    waypointCount: Int,
    onCaptureWaypoint: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenGallery: () -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    if (vertical) {
        Column(
            modifier = modifier
                .width(SideBandWidth)
                .fillMaxHeight()
                .background(Luna.Band)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                LeftSlot(
                    guided = selected.usesSequence,
                    waypointCount = waypointCount,
                    onCaptureWaypoint = onCaptureWaypoint,
                    onOpenGallery = onOpenGallery,
                    size = 36.dp,
                )
                HudIconButton(
                    icon = LunaIcons.Tune,
                    contentDescription = "Impostazioni della camera",
                    onClick = onOpenCameraSettings,
                    size = 36.dp,
                )
                HudIconButton(
                    icon = LunaIcons.Sequence,
                    contentDescription = "Automazioni del gimbal",
                    onClick = onOpenAutomations,
                    size = 36.dp,
                    selected = selected.usesSequence,
                    activeColor = Luna.Path,
                )
            }
            ShutterButton(
                mode = selected,
                active = active,
                progress = progress,
                ready = shutterReady,
                onClick = onShutter,
                diameter = 70.dp,
            )
        }
    } else {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(BottomBandHeight)
                .background(Luna.Band)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                LeftSlot(
                    guided = selected.usesSequence,
                    waypointCount = waypointCount,
                    onCaptureWaypoint = onCaptureWaypoint,
                    onOpenGallery = onOpenGallery,
                    size = 54.dp,
                )
            }
            ShutterButton(
                mode = selected,
                active = active,
                progress = progress,
                ready = shutterReady,
                onClick = onShutter,
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudIconButton(
                    icon = LunaIcons.Tune,
                    contentDescription = "Impostazioni della camera",
                    onClick = onOpenCameraSettings,
                    size = 44.dp,
                )
                HudIconButton(
                    icon = LunaIcons.Sequence,
                    contentDescription = "Automazioni del gimbal",
                    onClick = onOpenAutomations,
                    size = 44.dp,
                    selected = selected.usesSequence,
                    activeColor = Luna.Path,
                )
            }
        }
    }
}

/**
 * Il posto a sinistra dello scatto, che cambia con la modalità.
 *
 * Nelle modalità della camera è la galleria, come su qualunque app di ripresa; in quelle
 * guidate è «memorizza punto», che è il gesto che si ripete dieci volte di fila mentre si
 * costruisce un percorso. Metterli entrambi vorrebbe dire due bersagli piccoli invece di uno
 * grande, proprio dove il pollice arriva senza guardare.
 */
@Composable
private fun LeftSlot(
    guided: Boolean,
    waypointCount: Int,
    onCaptureWaypoint: () -> Unit,
    onOpenGallery: () -> Unit,
    size: androidx.compose.ui.unit.Dp,
) {
    if (guided) {
        HudIconButton(
            icon = LunaIcons.Waypoint,
            contentDescription = "Memorizza il punto corrente",
            onClick = onCaptureWaypoint,
            size = size,
            badge = waypointCount.takeIf { it > 0 }?.toString(),
            activeColor = Luna.Pano,
        )
    } else {
        HudIconButton(
            icon = LunaIcons.Gallery,
            contentDescription = "Galleria della camera",
            onClick = onOpenGallery,
            size = size,
        )
    }
}

