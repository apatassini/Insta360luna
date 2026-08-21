package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * La barra di scatto: ghiera delle modalità, pulsante di scatto e i due comandi che ci stanno
 * accanto — memorizzare un punto e aprire la sequenza.
 *
 * Sta in fondo in verticale e di lato in orizzontale, dove arriva il pollice della mano che
 * regge il telefono. È l'unica parte dei comandi che non si nasconde con un tocco: senza di
 * essa non si scatta.
 */
@Composable
fun CaptureBar(
    selected: CaptureMode,
    onSelect: (CaptureMode) -> Unit,
    active: Boolean,
    progress: Float,
    shutterEnabled: Boolean,
    onShutter: () -> Unit,
    waypointCount: Int,
    onCaptureWaypoint: () -> Unit,
    onOpenSequence: () -> Unit,
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
            modifier = modifier.width(118.dp).fillMaxHeight(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(modes, key = { it.name }) { mode ->
                    ModeItem(mode = mode, selected = mode == selected, onClick = { onSelect(mode) })
                }
            }
            HudIconButton(
                icon = LunaIcons.Waypoint,
                contentDescription = "Memorizza il punto corrente",
                onClick = onCaptureWaypoint,
                badge = waypointCount.takeIf { it > 0 }?.toString(),
            )
            ShutterButton(
                mode = selected,
                active = active,
                progress = progress,
                enabled = shutterEnabled,
                onClick = onShutter,
            )
            HudIconButton(
                icon = LunaIcons.Sequence,
                contentDescription = "Apri la sequenza",
                onClick = onOpenSequence,
            )
        }
    } else {
        Column(
            modifier = modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            LazyRow(
                state = listState,
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                contentPadding = PaddingValues(horizontal = 20.dp),
            ) {
                items(modes, key = { it.name }) { mode ->
                    ModeItem(mode = mode, selected = mode == selected, onClick = { onSelect(mode) })
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudIconButton(
                    icon = LunaIcons.Waypoint,
                    contentDescription = "Memorizza il punto corrente",
                    onClick = onCaptureWaypoint,
                    size = 50.dp,
                    badge = waypointCount.takeIf { it > 0 }?.toString(),
                )
                ShutterButton(
                    mode = selected,
                    active = active,
                    progress = progress,
                    enabled = shutterEnabled,
                    onClick = onShutter,
                )
                HudIconButton(
                    icon = LunaIcons.Sequence,
                    contentDescription = "Apri la sequenza",
                    onClick = onOpenSequence,
                    size = 50.dp,
                )
            }
        }
    }
}

@Composable
private fun ModeItem(
    mode: CaptureMode,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(16.dp)
    val tint = if (selected) Luna.Accent else Color.White.copy(alpha = 0.75f)
    Column(
        modifier = modifier
            .width(74.dp)
            .background(if (selected) Luna.Accent.copy(alpha = 0.16f) else Luna.GlassSoft, shape)
            .border(1.dp, if (selected) Luna.Accent.copy(alpha = 0.55f) else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(
            imageVector = mode.icon,
            contentDescription = mode.label,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
        Text(
            text = mode.shortLabel,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
