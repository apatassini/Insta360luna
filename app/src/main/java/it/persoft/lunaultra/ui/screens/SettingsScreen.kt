package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.protocol.LunaProtocolCodes
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Le impostazioni di tutti i giorni: la camera, l'anteprima, il comportamento del gimbal.
 *
 * Quelle da laboratorio — scanner dei codici, sonde, log grezzo — restano in Diagnostica: sono
 * due mestieri diversi e mescolarli significa far scorrere venti righe tecniche a chi voleva
 * solo cambiare la velocità del movimento.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: MainViewModel, onOpenDiagnostics: () -> Unit) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val preview by viewModel.preview.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val calibration by viewModel.gimbalCalibration.collectAsState()
    val calibrationState by viewModel.gimbalCalibrationState.collectAsState()
    val connected = connection == ConnectionState.CONNECTED
    val gimbal = settings.gimbal
    var wifiPasswordVisible by rememberSaveable { mutableStateOf(false) }

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
                OutlinedTextField(
                    value = settings.cameraWifiPassword,
                    onValueChange = viewModel::setCameraWifiPassword,
                    label = { Text("Password Wi-Fi Luna") },
                    supportingText = {
                        Text(
                            if (settings.cameraWifiPassword.isBlank()) {
                                "Inseriscila una volta, oppure connettiti manualmente: l'app proverà a leggerla dalla camera."
                            } else {
                                "Salvata: la rete Luna verrà selezionata automaticamente all'avvio."
                            }
                        )
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation = if (wifiPasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { wifiPasswordVisible = !wifiPasswordVisible }) {
                            Icon(
                                imageVector = if (wifiPasswordVisible) LunaIcons.Hide else LunaIcons.Show,
                                contentDescription = if (wifiPasswordVisible) "Nascondi password" else "Mostra password",
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
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

        SectionCard(title = "Panorama della camera", icon = LunaIcons.Panorama, accent = Luna.Pano) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(
                        LunaProtocolCodes.PanoAspect.SPHERE_360 to "Sferica 360°",
                        LunaProtocolCodes.PanoAspect.RATIO_2_1 to "Panorama 2:1",
                    ).forEach { (aspect, label) ->
                        FilterChip(
                            selected = settings.panoAspect == aspect,
                            onClick = { viewModel.setPanoAspect(aspect) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.Pano.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.Pano,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "Sulla Luna Ultra la panoramica è una sola sotto-modalità della camera — " +
                        "photo_sub_mode 8 per entrambe — e la scelta fra sferica e 2:1 viaggia su " +
                        "un campo a parte, che è questo. Si può cambiare anche dal mirino, con la " +
                        "pastiglia sopra il pulsante di scatto.",
                )
            }
        }

        SectionCard(title = "Movimento manuale", icon = LunaIcons.Joystick, accent = Luna.PathLapse) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SliderRow(
                    label = "Intensità della levetta",
                    value = gimbal.manualSpeedPercent.toFloat(),
                    onValueChange = { viewModel.setManualSpeed(it.roundToInt()) },
                    valueRange = 1f..100f,
                    valueLabel = "${gimbal.manualSpeedPercent}%",
                    icon = LunaIcons.Speed,
                )
                Text("Velocità hardware del gimbal")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    listOf(1 to "Lenta", 2 to "Media", 3 to "Veloce").forEach { (level, label) ->
                        FilterChip(
                            selected = gimbal.hardwareSpeedLevel == level,
                            onClick = { viewModel.setGimbalHardwareSpeed(level) },
                            label = { Text(label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.PathLapse.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.PathLapse,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint("Questi tre livelli vengono scritti nella camera; non sono una semplice scala grafica.")
                ToggleRow(
                    title = "Correzione visiva automatica",
                    subtitle = "Allinea Punto 1 e arrivi confrontando i punti di controllo delle miniature",
                    checked = gimbal.visualWaypointCorrection,
                    onCheckedChange = { on ->
                        viewModel.updateGimbal { it.copy(visualWaypointCorrection = on) }
                    },
                    icon = LunaIcons.Center,
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
                    value = "226 · 0x00E2 verificato",
                    valueColor = Luna.Ok,
                )
            }
        }

        SectionCard(title = "Calibrazione gimbal", icon = LunaIcons.Center, accent = Luna.Pano) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (calibrationState.running) {
                    Text(calibrationState.message)
                    LinearProgressIndicator(
                        progress = { calibrationState.progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    LabeledValue(
                        "Avanzamento",
                        "${calibrationState.completedSteps}/${calibrationState.totalSteps} · ${(calibrationState.progress * 100).roundToInt()}%",
                    )
                    OutlinedButton(
                        onClick = viewModel::cancelGimbalCalibration,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Interrompi calibrazione")
                    }
                } else {
                    if (calibration.isValid) {
                        LabeledValue("Profilo attivo", formatCalibrationDate(calibration.calibratedAtMs), Luna.Ok)
                        LabeledValue("Qualità misure", "${calibration.qualityPercent}% · ${calibration.validSamples}/${calibration.totalSamples}")
                        LabeledValue("Ritardo / assestamento", "${calibration.responseOverheadMs} ms / ${calibration.settleMs} ms")
                        calibration.levels.sortedBy { it.hardwareLevel }.forEach { level ->
                            LabeledValue(
                                when (level.hardwareLevel) { 1 -> "Lenta"; 2 -> "Media"; else -> "Veloce" },
                                "pan %+.0f px/s · tilt %+.0f px/s".format(
                                    level.panImagePixelsPerSecond,
                                    level.tiltImagePixelsPerSecond,
                                ),
                            )
                        }
                    } else {
                        LabeledValue("Profilo attivo", "Non ancora calibrato")
                    }
                    calibrationState.error?.let { Hint("Ultimo tentativo: $it") }
                    Button(
                        onClick = viewModel::startGimbalCalibration,
                        enabled = connected,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(LunaIcons.Center, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(if (calibration.isValid) "  Riesegui calibrazione completa" else "  Avvia calibrazione completa")
                    }
                }
                Hint(
                    "Durata circa 2 minuti. Lascia la camera libera di ruotare, inquadra una " +
                        "scena ferma e ricca di dettagli e non toccarla. Vengono misurati Lenta, " +
                        "Media e Veloce sui due assi; il profilo precedente resta valido se la " +
                        "nuova prova viene interrotta o non è affidabile.",
                )
                if (calibration.isValid) {
                    Hint(
                        "Il file gimbal_calibration.json viene caricato a ogni avvio e usato " +
                            "automaticamente per tempi, direzioni e correzione visiva dei waypoint.",
                    )
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

        SectionCard(title = "Log diagnostico", icon = LunaIcons.Diagnostics, accent = Luna.Accent) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabeledValue("Eventi presenti", logEntries.size.toString())
                Button(
                    onClick = { viewModel.saveLogToDownloads(context) },
                    enabled = logEntries.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(LunaIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Salva log nella cartella Download")
                }
                Hint(
                    "Viene creato un HTML con testo, miniature e punti di controllo incorporati. " +
                        "Il log dell'app viene azzerato soltanto dopo che il file è stato salvato correttamente.",
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

private fun formatCalibrationDate(timestampMs: Long): String =
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.ITALIAN).format(Date(timestampMs))
