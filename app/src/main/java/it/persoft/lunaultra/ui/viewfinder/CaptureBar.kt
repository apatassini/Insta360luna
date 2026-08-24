package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
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
 * La fascia di scatto: lo scatto al centro, e i comandi che si usano guardando l'inquadratura.
 *
 * A sinistra ci sono sempre tutti e tre: la galleria, memorizza punto, e togli l'ultimo punto.
 * Prima galleria e punto si davano il cambio a seconda della modalità, e sembrava una buona
 * idea — un bersaglio grande invece di due piccoli — ma significava che per rivedere uno scatto
 * bisognava cambiare modalità, e che il punto appena memorizzato per sbaglio restava lì fino al
 * pannello delle automazioni. Un comando che c'è solo a volte va cercato ogni volta; tre
 * comandi sempre allo stesso posto si imparano una volta sola.
 *
 * Togliere l'ultimo punto e non svuotare tutto: un percorso si costruisce un punto per volta
 * guardando l'inquadratura, e l'errore che si fa è memorizzare quello appena sbagliato.
 * Svuotare resta nelle automazioni, dove si vede la lista e si sa cosa si sta buttando via.
 *
 * La ghiera delle modalità non è più qui: occupava una fascia intera per una scelta che si fa
 * una volta ogni tanto. Si cambia modalità dal distintivo in cima.
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
    onRemoveLastWaypoint: () -> Unit,
    onOpenCameraSettings: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenGallery: () -> Unit,
    vertical: Boolean,
    modifier: Modifier = Modifier,
    latestThumb: android.graphics.Bitmap? = null,
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
                    onRemoveLastWaypoint = onRemoveLastWaypoint,
                    onOpenGallery = onOpenGallery,
                    latestThumb = latestThumb,
                    size = 34.dp,
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
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                LeftSlot(
                    guided = selected.usesSequence,
                    waypointCount = waypointCount,
                    onCaptureWaypoint = onCaptureWaypoint,
                    onRemoveLastWaypoint = onRemoveLastWaypoint,
                    onOpenGallery = onOpenGallery,
                    latestThumb = latestThumb,
                    size = 42.dp,
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
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HudIconButton(
                    icon = LunaIcons.Tune,
                    contentDescription = "Impostazioni della camera",
                    onClick = onOpenCameraSettings,
                    size = 42.dp,
                )
                HudIconButton(
                    icon = LunaIcons.Sequence,
                    contentDescription = "Automazioni del gimbal",
                    onClick = onOpenAutomations,
                    size = 42.dp,
                    selected = selected.usesSequence,
                    activeColor = Luna.Path,
                )
            }
        }
    }
}

/**
 * I tre comandi a sinistra dello scatto, sempre gli stessi e sempre nello stesso ordine.
 *
 * Galleria, memorizza punto, togli l'ultimo punto. Il numero sul secondo dice quanti punti ci
 * sono: senza, «togli l'ultimo» non si sa se ha qualcosa da togliere — e infatti il terzo si
 * spegne quando la lista è vuota, invece di essere un pulsante che non fa niente.
 *
 * Nelle modalità guidate il pulsante del punto si accende del colore del percorso: è quello che
 * si sta usando, e lo si trova con la coda dell'occhio senza staccarsi dall'inquadratura.
 */
@Composable
private fun LeftSlot(
    guided: Boolean,
    waypointCount: Int,
    onCaptureWaypoint: () -> Unit,
    onRemoveLastWaypoint: () -> Unit,
    onOpenGallery: () -> Unit,
    latestThumb: android.graphics.Bitmap?,
    size: androidx.compose.ui.unit.Dp,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Il pulsante della galleria è l'ultima foto: quando la miniatura cambia, lo scatto
        // è sulla scheda. È la conferma che non ha bisogno di parole, la stessa dell'app
        // ufficiale. Finché non c'è nessuna foto nota resta l'icona.
        if (latestThumb != null) {
            Image(
                bitmap = latestThumb.asImageBitmap(),
                contentDescription = "Galleria della camera · ultima foto",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape)
                    .border(1.dp, Luna.GlassBorder, CircleShape)
                    .clickable(onClick = onOpenGallery),
            )
        } else {
            HudIconButton(
                icon = LunaIcons.Gallery,
                contentDescription = "Galleria della camera",
                onClick = onOpenGallery,
                size = size,
            )
        }
        HudIconButton(
            icon = LunaIcons.Waypoint,
            contentDescription = "Memorizza il punto corrente",
            onClick = onCaptureWaypoint,
            size = size,
            badge = waypointCount.takeIf { it > 0 }?.toString(),
            selected = guided,
            activeColor = Luna.Path,
        )
        HudIconButton(
            icon = LunaIcons.Delete,
            contentDescription = "Togli l'ultimo punto memorizzato",
            onClick = onRemoveLastWaypoint,
            size = size,
            enabled = waypointCount > 0,
        )
    }
}
