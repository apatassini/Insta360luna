package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.LunaCommand
import it.persoft.lunaultra.camera.LunaNotification
import it.persoft.lunaultra.data.GimbalDriveMode
import it.persoft.lunaultra.net.LogLevel
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val scanning by viewModel.scanning.collectAsState()
    val scanResults by viewModel.scanResults.collectAsState()

    var rawCommand by rememberSaveable { mutableStateOf("") }
    var rawPayload by rememberSaveable { mutableStateOf("") }
    var scanFrom by rememberSaveable { mutableStateOf("1") }
    var scanTo by rememberSaveable { mutableStateOf("200") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionCard(title = "Id dei comandi") {
            Text(
                "Gli id numerici del protocollo non sono pubblici. Inseriscili qui man mano che li " +
                    "ricavi da una cattura o dallo scanner qui sotto: un id a 0 disabilita il comando.",
                style = MaterialTheme.typography.bodySmall,
            )
            LunaCommand.entries.forEach { command ->
                NumberField(
                    label = "${command.label} · ${command.key}",
                    value = (settings.commandIds[command.key] ?: 0).toString(),
                    onValueChange = { text -> text.toIntOrNull()?.let { viewModel.setCommandId(command, it) } },
                    supportingText = command.description,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            LunaNotification.entries.forEach { notification ->
                NumberField(
                    label = "${notification.label} · ${notification.key}",
                    value = (settings.notificationIds[notification.key] ?: 0).toString(),
                    onValueChange = { text -> text.toIntOrNull()?.let { viewModel.setNotificationId(notification, it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            NumberField(
                label = "Valore modalità Timelapse",
                value = settings.timelapseModeValue.toString(),
                onValueChange = { text -> text.toIntOrNull()?.let(viewModel::setTimelapseModeValue) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        SectionCard(title = "Layout dei frame") {
            Text(
                "Header binario davanti al payload protobuf. Confronta i byte ricevuti (log RX) " +
                    "con questi valori finché i frame vengono decodificati senza scarti.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Header (byte)",
                    value = settings.layout.headerSize.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { v -> viewModel.updateLayout { it.copy(headerSize = v) } } },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Offset lunghezza",
                    value = settings.layout.lengthOffset.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { v -> viewModel.updateLayout { it.copy(lengthOffset = v) } } },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Offset comando",
                    value = settings.layout.commandOffset.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { v -> viewModel.updateLayout { it.copy(commandOffset = v) } } },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Offset sequenza",
                    value = settings.layout.sequenceOffset.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { v -> viewModel.updateLayout { it.copy(sequenceOffset = v) } } },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = settings.layout.lengthIncludesHeader,
                    onCheckedChange = { v -> viewModel.updateLayout { it.copy(lengthIncludesHeader = v) } },
                )
                Text("La lunghezza include l'header", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = settings.layout.littleEndian,
                    onCheckedChange = { v -> viewModel.updateLayout { it.copy(littleEndian = v) } },
                )
                Text("Little endian", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = settings.layout.validateVersion,
                    onCheckedChange = { v -> viewModel.updateLayout { it.copy(validateVersion = v) } },
                )
                Text("Usa il byte di versione per risincronizzare", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Valore versione",
                    value = settings.layout.version.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { v -> viewModel.updateLayout { it.copy(version = v) } } },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Offset versione",
                    value = settings.layout.versionOffset.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { v -> viewModel.updateLayout { it.copy(versionOffset = v) } } },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = settings.handshakeEnabled,
                    onCheckedChange = { v -> viewModel.updateHandshake(v) },
                )
                Text("Handshake alla connessione", style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard(title = "Parametri gimbal") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GimbalDriveMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.gimbal.driveMode == mode,
                        onClick = { viewModel.setDriveMode(mode) },
                        label = { Text(if (mode == GimbalDriveMode.VELOCITY) "Velocità" else "Posizione") },
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Pan max (°/s)",
                    value = settings.gimbal.maxPanSpeedDegPerSec.toString(),
                    onValueChange = { t -> t.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxPanSpeedDegPerSec = v) } } },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Tilt max (°/s)",
                    value = settings.gimbal.maxTiltSpeedDegPerSec.toString(),
                    onValueChange = { t -> t.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxTiltSpeedDegPerSec = v) } } },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Campo pan",
                    value = settings.gimbal.panFieldNumber.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { v -> viewModel.updateGimbal { it.copy(panFieldNumber = v) } } },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Campo tilt",
                    value = settings.gimbal.tiltFieldNumber.toString(),
                    onValueChange = { t -> t.toIntOrNull()?.let { v -> viewModel.updateGimbal { it.copy(tiltFieldNumber = v) } } },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Scala angoli",
                    value = settings.gimbal.angleScale.toString(),
                    onValueChange = { t -> t.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(angleScale = v) } } },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = settings.gimbal.invertPan,
                    onCheckedChange = { v -> viewModel.updateGimbal { it.copy(invertPan = v) } },
                )
                Text("Inverti pan", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = settings.gimbal.invertTilt,
                    onCheckedChange = { v -> viewModel.updateGimbal { it.copy(invertTilt = v) } },
                )
                Text("Inverti tilt", style = MaterialTheme.typography.bodyMedium)
            }
        }

        SectionCard(title = "Invio manuale") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Id comando (dec o 0x…)",
                    value = rawCommand,
                    onValueChange = { rawCommand = it },
                    keyboardType = KeyboardType.Text,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Payload hex",
                    value = rawPayload,
                    onValueChange = { rawPayload = it },
                    keyboardType = KeyboardType.Text,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(onClick = { viewModel.sendRaw(rawCommand, rawPayload) }, modifier = Modifier.fillMaxWidth()) {
                Text("Invia frame")
            }
        }

        SectionCard(title = "Scanner comandi") {
            Text(
                "Invia un payload vuoto a ogni id nell'intervallo e annota chi risponde. " +
                    "Da usare con la camera ferma e senza registrazione in corso.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Da",
                    value = scanFrom,
                    onValueChange = { scanFrom = it },
                    keyboardType = KeyboardType.Text,
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "A",
                    value = scanTo,
                    onValueChange = { scanTo = it },
                    keyboardType = KeyboardType.Text,
                    modifier = Modifier.weight(1f),
                )
            }
            Button(
                onClick = { viewModel.scanCommands(scanFrom, scanTo) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (scanning) "Interrompi scansione" else "Avvia scansione")
            }
            scanResults.forEach { result ->
                LabeledValue(
                    "0x%04X (%d)".format(result.commandId, result.commandId),
                    "err=${result.errorCode} · ${result.payloadSize} byte",
                )
            }
        }

        SectionCard(
            title = "Log",
            trailing = {
                OutlinedButton(onClick = viewModel::clearLog) { Text("Pulisci") }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState())
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(8.dp),
            ) {
                logEntries.takeLast(200).forEach { entry ->
                    Text(
                        text = "${entry.time} ${entry.message}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = when (entry.level) {
                            LogLevel.ERROR -> MaterialTheme.colorScheme.error
                            LogLevel.WARN -> MaterialTheme.colorScheme.secondary
                            LogLevel.TX -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
