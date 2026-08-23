package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons

/** Altezza della fascia inferiore, esclusa la barra di sistema. */
val BottomBandHeight = 148.dp

/** Larghezza della fascia dei comandi quando passa di lato, in orizzontale. */
val SideBandWidth = 124.dp

/**
 * La fascia di scatto: ghiera delle modalità e i quattro comandi che le stanno attorno.
 *
 * L'impaginazione è quella di una camera perché è quella che il pollice conosce già: memorizza
 * punto a sinistra, scatto al centro, regolazioni a destra, e sotto la ghiera con la modalità
 * accesa nel suo colore.
 */
@Composable
fun CaptureBar(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    active: Boolean,
    progress: Float,
    shutterReady: Boolean,
    onShutter: () -> Unit,
    waypointCount: Int,
    onCaptureWaypoint: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenModeSheet: () -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
) {
    val modes = CaptureMode.entries
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        // La ghiera segue la selezione: cambiare modalità dal pannello e ritrovarla fuori
        // schermo qui sarebbe l'unico modo di non sapere più in che modalità si è.
        listState.animateScrollToItem(modes.indexOf(selected))
    }

    if (vertical) {
        Column(
            modifier = modifier
                .width(SideBandWidth)
                .fillMaxHeight()
                .background(Luna.Band)
                .padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(modes, key = { it.name }) { mode ->
                    ModeLabel(
                        mode = mode,
                        selected = mode == selected,
                        onClick = { onSelect(mode) },
                        modifier = Modifier.width(112.dp),
                    )
                }
            }
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
        Column(
            modifier = modifier
                .fillMaxWidth()
                .height(BottomBandHeight)
                .background(Luna.Band),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
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

            Row(
                modifier = Modifier.fillMaxWidth().height(46.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LazyRow(
                    state = listState,
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    items(modes, key = { it.name }) { mode ->
                        ModeLabel(
                            mode = mode,
                            selected = mode == selected,
                            onClick = { onSelect(mode) },
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clickable(onClick = onOpenModeSheet),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = LunaIcons.Menu,
                        contentDescription = "Tutte le modalità",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
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

/** Voce della ghiera: accesa nel colore della modalità, spenta in bianco smorzato. */
@Composable
private fun ModeLabel(
    mode: CaptureMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = mode.label,
            style = if (selected) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            color = if (selected) mode.color else Color.White.copy(alpha = 0.55f),
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
        Box(
            modifier = Modifier
                .size(width = if (selected) 16.dp else 0.dp, height = 3.dp)
                .background(mode.color, CircleShape),
        )
    }
}
