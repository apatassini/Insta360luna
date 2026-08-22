package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard
import it.persoft.lunaultra.ui.components.SliderRow
import it.persoft.lunaultra.ui.components.ToggleRow
import it.persoft.lunaultra.ui.formatBytes
import it.persoft.lunaultra.ui.italianLabel
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * Le impostazioni di tutti i giorni: la camera, l'anteprima, il comportamento del gimbal.
 *
 * Quelle da laboratorio — scanner dei codici, sonde, log grezzo — restano in Diagnostica: sono
 * due mestieri diversi e mescolarli significa far scorrere venti righe tecniche a chi voleva
 * solo cambiare la velocità del movimento.
 */
@Composable
fun SettingsScreen(viewModel: MainViewModel, onOpenDiagnostics: () -> Unit) {
    val settings by viewModel.settings.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val connected = connection == ConnectionState.CONNECTED
    val gimbal = settings.gimbal

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionCard(title = "Camera", icon = LunaIcons.Connected, accent = Luna.Ok) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "Indirizzo",
                        value = settings.host,
                        onValueChange = viewModel::setHost,
                        keyboardType = KeyboardType.Text,
                        modifier = Modifier.weight(2f),
                    )
                    NumberField(
                        label = "Porta",
                        value = settings.port.toString(),
                        onValueChange = { text -> text.toIntOrNull()?.let(viewModel::setPort) },
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = { if (connected) viewModel.disconnect() else viewModel.connect() },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = if (connected) LunaIcons.Disconnected else LunaIcons.Connected,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(text = if (connected) "  Disconnetti" else "  Connetti")
                    }
                    OutlinedButton(
                        onClick = viewModel::refreshStatus,
                        enabled = connected,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(LunaIcons.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text("  Aggiorna")
                    }
                }
                LabeledValue(
                    label = "Stato",
                    value = connection.italianLabel(),
                    valueColor = if (connected) Luna.Ok else Luna.OnSurfaceDim,
                )
                LabeledValue("Batteria", status.batteryPercent?.let { "$it%" } ?: "—")
                LabeledValue("Spazio libero", status.freeSpaceBytes?.let(::formatBytes) ?: "—")
                LabeledValue("Modello", status.model ?: "—")
                LabeledValue("Seriale", status.serial ?: "—")
                LabeledValue("Firmware", status.firmware ?: "—")
            }
        }

        SectionCard(title = "Anteprima", icon = LunaIcons.Video, accent = Luna.Movie) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LabeledValue("Sorgente", preview.source.name.lowercase())
                LabeledValue("Fotogrammi decodificati", preview.framesDecoded.toString())
                LabeledValue("Byte ricevuti", formatBytes(preview.bytesReceived))
                preview.message?.let { Hint(it) }
                Button(
                    onClick = viewModel::togglePreview,
                    enabled = connected || preview.active,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = if (preview.active) LunaIcons.VideoOff else LunaIcons.Video,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(text = if (preview.active) "  Spegni l'anteprima" else "  Accendi l'anteprima")
                }
                Hint(
                    "Si prova prima il MJPEG dell'endpoint OSC; se la camera non lo offre si passa " +
                        "allo stream della sessione di controllo, che è video compresso e passa dal decoder.",
                )
            }
        }

        SectionCard(title = "Movimento manuale", icon = LunaIcons.Joystick, accent = Luna.PathLapse) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SliderRow(
                    label = "Velocità della levetta",
                    value = gimbal.manualSpeedPercent.toFloat(),
                    onValueChange = { viewModel.setManualSpeed(it.roundToInt()) },
                    valueRange = 1f..100f,
                    valueLabel = "${gimbal.manualSpeedPercent}%",
                    icon = LunaIcons.Speed,
                )
                ToggleRow(
                    title = "Inverti l'asse orizzontale",
                    subtitle = "Se il gimbal va a destra quando spingi a sinistra",
                    checked = gimbal.invertPan,
                    onCheckedChange = { on -> viewModel.updateGimbal { it.copy(invertPan = on) } },
                    icon = LunaIcons.Axis,
                )
                ToggleRow(
                    title = "Inverti l'asse verticale",
                    subtitle = "Stessa cosa per l'inclinazione",
                    checked = gimbal.invertTilt,
                    onCheckedChange = { on -> viewModel.updateGimbal { it.copy(invertTilt = on) } },
                    icon = LunaIcons.Axis,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        label = "Pan max °/s",
                        value = gimbal.maxPanSpeedDegPerSec.toString(),
                        onValueChange = { text ->
                            text.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxPanSpeedDegPerSec = v) } }
                        },
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = "Tilt max °/s",
                        value = gimbal.maxTiltSpeedDegPerSec.toString(),
                        onValueChange = { text ->
                            text.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxTiltSpeedDegPerSec = v) } }
                        },
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                    )
                }
                Hint(
                    "Queste due velocità legano la durata impostata al movimento reale: si tarano " +
                        "cronometrando una rotazione completa e correggendo il numero.",
                )
                LabeledValue(
                    label = "Comando gimbal",
                    value = if (gimbal.isControlCodeKnown) gimbal.controlCode.toString() else "non ancora noto",
                    valueColor = if (gimbal.isControlCodeKnown) Luna.Ok else Luna.Warn,
                )
                OutlinedButton(onClick = onOpenDiagnostics, modifier = Modifier.fillMaxWidth()) {
                    Icon(LunaIcons.Diagnostics, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Cerca il comando in Diagnostica")
                }
            }
        }

        SectionCard(title = "Timelapse della camera", icon = LunaIcons.Timelapse, accent = Luna.Lapse) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    label = "Modalità timelapse",
                    value = settings.timelapseMode.toString(),
                    onValueChange = { text -> text.toIntOrNull()?.let(viewModel::setTimelapseMode) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    "È il valore di TimelapseMode inviato con i comandi di timelapse interno. " +
                        "Si cambia solo se la camera rifiuta l'avvio.",
                )
            }
        }

        SectionCard(title = "Su questa app", icon = LunaIcons.Info, accent = Luna.Photo) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Controllo non ufficiale della Insta360 Luna Ultra, basato sul protocollo " +
                        "ricostruito da progetti di reverse engineering indipendenti.",
                )
                Hint(
                    "Il numero del comando del gimbal non è pubblico: finché non viene trovato, i " +
                        "comandi di movimento restano visibili ma inerti, e la sequenza non parte.",
                )
            }
        }
    }
}
