package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.CameraStatus
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.preview.PreviewSource
import it.persoft.lunaultra.preview.PreviewState
import it.persoft.lunaultra.ui.components.HudCaption
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.components.HudPill
import it.persoft.lunaultra.ui.components.RecordingPill
import it.persoft.lunaultra.ui.formatBytes
import it.persoft.lunaultra.ui.italianLabel
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons

/**
 * La riga di stato sopra l'anteprima: quello che serve sapere senza aprire niente.
 *
 * Batteria e spazio stanno qui e non in un pannello perché sono le due cose che fanno fallire
 * una ripresa lunga, e una volta partita la sequenza nessuno va a cercarle in un menu.
 */
@Composable
fun ViewfinderHud(
    connection: ConnectionState,
    status: CameraStatus,
    subtitle: String,
    recordingLabel: String?,
    onToggleConnection: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSequence: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onRefreshStatus: () -> Unit,
    onShareLog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val connected = connection == ConnectionState.CONNECTED

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HudPill(
                    text = connection.italianLabel(),
                    icon = if (connected) LunaIcons.Connected else LunaIcons.Disconnected,
                    tint = when (connection) {
                        ConnectionState.CONNECTED -> Luna.Ok
                        ConnectionState.ERROR -> Luna.Rec
                        ConnectionState.CONNECTING, ConnectionState.HANDSHAKE -> Luna.Warn
                        ConnectionState.DISCONNECTED -> Luna.OnSurfaceDim
                    },
                    onClick = onToggleConnection,
                )
                status.batteryPercent?.let { percent ->
                    HudPill(
                        text = "$percent%",
                        icon = LunaIcons.Battery,
                        tint = when {
                            percent <= 15 -> Luna.Rec
                            percent <= 35 -> Luna.Warn
                            else -> Color.White
                        },
                    )
                }
                status.freeSpaceBytes?.let { free ->
                    HudPill(text = formatBytes(free), icon = LunaIcons.Storage, tint = Color.White)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HudIconButton(
                    icon = LunaIcons.Tune,
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
                        DropdownMenuItem(
                            text = { Text("Sequenza e punti") },
                            leadingIcon = { Icon(LunaIcons.Sequence, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenSequence()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Aggiorna stato camera") },
                            leadingIcon = { Icon(LunaIcons.Refresh, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onRefreshStatus()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Diagnostica") },
                            leadingIcon = { Icon(LunaIcons.Diagnostics, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onOpenDiagnostics()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Condividi il log") },
                            leadingIcon = { Icon(LunaIcons.Share, contentDescription = null) },
                            onClick = {
                                menuOpen = false
                                onShareLog()
                            },
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (recordingLabel != null) RecordingPill(label = recordingLabel)
            HudCaption(text = subtitle)
        }
    }
}

/** Riga di contesto sotto lo stato: modalità, punti memorizzati, sorgente dell'anteprima. */
fun viewfinderSubtitle(mode: CaptureMode, waypoints: Int, preview: PreviewState): String {
    val parts = mutableListOf(mode.label)
    if (mode.usesSequence) {
        parts += if (waypoints == 0) "nessun punto" else "$waypoints punti"
    }
    if (preview.active && preview.source != PreviewSource.NESSUNA) {
        parts += "anteprima ${preview.source.name.lowercase()}"
    }
    return parts.joinToString("  ·  ")
}
