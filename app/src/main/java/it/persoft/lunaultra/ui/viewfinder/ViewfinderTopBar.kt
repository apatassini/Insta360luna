package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.CameraStatus
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.components.RecordingPill
import it.persoft.lunaultra.ui.formatBytes
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons

/** Altezza della fascia superiore, esclusa la barra di sistema. */
val TopBandHeight = 56.dp

/**
 * La fascia dei comandi sopra l'anteprima.
 *
 * Piena e non trasparente, come su ogni app di ripresa: l'immagine si legge meglio dentro una
 * cornice netta che sotto una sfumatura, e le icone non devono cambiare contrasto a seconda di
 * cosa si sta inquadrando.
 */
@Composable
fun ViewfinderTopBar(
    connection: ConnectionState,
    mode: CaptureMode,
    badgeDetail: String?,
    previewActive: Boolean,
    gridVisible: Boolean,
    fillScreen: Boolean,
    onToggleConnection: () -> Unit,
    onTogglePreview: () -> Unit,
    onToggleGrid: () -> Unit,
    onToggleFill: () -> Unit,
    onHideChrome: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSequence: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRefreshStatus: () -> Unit,
    onShareLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val connected = connection == ConnectionState.CONNECTED
    val connectionColor = when (connection) {
        ConnectionState.CONNECTED -> Luna.Ok
        ConnectionState.ERROR -> Luna.Rec
        ConnectionState.CONNECTING, ConnectionState.HANDSHAKE -> Luna.Warn
        ConnectionState.DISCONNECTED -> Luna.OnSurfaceDim
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(TopBandHeight)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HudIconButton(
            icon = if (connected) LunaIcons.Connected else LunaIcons.Disconnected,
            contentDescription = if (connected) "Disconnetti" else "Connetti",
            onClick = onToggleConnection,
            size = 40.dp,
            selected = connected,
            activeColor = connectionColor,
        )

        // Il distintivo può restringersi: su schermi stretti è lui a cedere spazio, non le icone.
        ModeBadge(mode = mode, detail = badgeDetail, modifier = Modifier.weight(1f, fill = false))

        Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
            HudIconButton(
                icon = if (previewActive) LunaIcons.Video else LunaIcons.VideoOff,
                contentDescription = if (previewActive) "Spegni l'anteprima" else "Accendi l'anteprima",
                onClick = onTogglePreview,
                size = 40.dp,
                selected = previewActive,
                activeColor = Luna.Path,
            )
            HudIconButton(
                icon = if (gridVisible) LunaIcons.Grid else LunaIcons.GridOff,
                contentDescription = "Griglia dei terzi",
                onClick = onToggleGrid,
                size = 40.dp,
                selected = gridVisible,
                activeColor = Luna.Photo,
            )
            HudIconButton(
                icon = LunaIcons.Settings,
                contentDescription = "Impostazioni",
                onClick = onOpenSettings,
                size = 40.dp,
            )
            Box {
                HudIconButton(
                    icon = LunaIcons.More,
                    contentDescription = "Altre azioni",
                    onClick = { menuOpen = true },
                    size = 40.dp,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    MenuRow(
                        label = if (fillScreen) "Adatta l'immagine" else "Riempi lo schermo",
                        icon = if (fillScreen) LunaIcons.Fit else LunaIcons.Fill,
                    ) {
                        menuOpen = false
                        onToggleFill()
                    }
                    MenuRow(label = "Nascondi i comandi", icon = LunaIcons.Hide) {
                        menuOpen = false
                        onHideChrome()
                    }
                    MenuRow(label = "Galleria della camera", icon = LunaIcons.Gallery) {
                        menuOpen = false
                        onOpenGallery()
                    }
                    MenuRow(label = "Sequenza e punti", icon = LunaIcons.Sequence) {
                        menuOpen = false
                        onOpenSequence()
                    }
                    MenuRow(label = "Aggiorna stato camera", icon = LunaIcons.Refresh) {
                        menuOpen = false
                        onRefreshStatus()
                    }
                    MenuRow(label = "Diagnostica", icon = LunaIcons.Diagnostics) {
                        menuOpen = false
                        onOpenDiagnostics()
                    }
                    MenuRow(label = "Condividi il log", icon = LunaIcons.Share) {
                        menuOpen = false
                        onShareLog()
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(label) },
        leadingIcon = { Icon(icon, contentDescription = null, tint = Luna.Accent) },
        onClick = onClick,
    )
}

/** Distintivo della modalità in corso, al centro della fascia: porta il colore della modalità. */
@Composable
private fun ModeBadge(mode: CaptureMode, detail: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(9.dp)
    Row(
        modifier = modifier
            .background(mode.color.copy(alpha = 0.16f), shape)
            .border(1.dp, mode.color.copy(alpha = 0.7f), shape)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(mode.icon, contentDescription = null, tint = mode.color, modifier = Modifier.size(15.dp))
        Text(
            text = mode.shortLabel,
            style = MaterialTheme.typography.labelMedium,
            color = mode.color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * La colonna dei numeri, in alto a sinistra sull'immagine.
 *
 * Sono le tre cose che fanno fallire una ripresa lunga — spazio, batteria, gimbal pronto o no —
 * e stanno sull'anteprima perché è lì che si guarda mentre si riprende.
 */
@Composable
fun StatColumn(
    freeSpace: Long?,
    batteryPercent: Int?,
    gimbalReady: Boolean,
    recordingLabel: String?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (recordingLabel != null) RecordingPill(label = recordingLabel)
        StatRow(
            icon = LunaIcons.Storage,
            tint = Luna.Path,
            value = freeSpace?.let(::formatBytes) ?: "—",
        )
        StatRow(
            icon = LunaIcons.Battery,
            tint = when {
                batteryPercent == null -> Luna.OnSurfaceDim
                batteryPercent <= 15 -> Luna.Rec
                batteryPercent <= 35 -> Luna.Warn
                else -> Luna.Pano
            },
            value = batteryPercent?.let { "$it%" } ?: "—",
        )
        StatRow(
            icon = LunaIcons.Joystick,
            tint = if (gimbalReady) Luna.Pano else Luna.Warn,
            value = if (gimbalReady) "gimbal pronto" else "gimbal ignoto",
        )
    }
}

@Composable
private fun StatRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
    value: String,
) {
    Row(
        modifier = Modifier
            .background(Luna.GlassSoft, CircleShape)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(15.dp))
        Text(text = value, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/** Riga di contesto per il distintivo: punti memorizzati o stato della camera. */
fun badgeDetailFor(mode: CaptureMode, waypoints: Int, status: CameraStatus): String? = when {
    mode.usesSequence -> "${waypoints}P"
    status.captureMode != null -> status.captureMode
    else -> null
}
