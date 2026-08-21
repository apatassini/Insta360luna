package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.CodeProbe
import it.persoft.lunaultra.net.LogLevel
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard

/**
 * Diagnostica: è qui che si chiude l'unico buco rimasto del protocollo, il numero del comando
 * del gimbal. Il resto della schermata serve a guardare cosa passa sul canale di controllo.
 */
@Composable
fun DiagnosticsScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val probe by viewModel.probe.collectAsState()
    val sightings by viewModel.sightings.collectAsState()
    val log by viewModel.logEntries.collectAsState()
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        // In cima, non in fondo: il log serve a essere mandato, e cercare il pulsante in fondo
        // a una schermata lunga significa scorrere tutto ogni volta.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = { viewModel.shareLog(context) },
                modifier = Modifier.weight(1f),
            ) { Text("Condividi log") }
            OutlinedButton(
                onClick = viewModel::clearLog,
                modifier = Modifier.weight(1f),
            ) { Text("Pulisci log") }
        }

        SectionCard(title = "Connessione") {
            var host by remember(settings.host) { mutableStateOf(settings.host) }
            var port by remember(settings.port) { mutableStateOf(settings.port.toString()) }
            OutlinedTextField(
                value = host,
                onValueChange = { host = it; viewModel.setHost(it) },
                label = { Text("Indirizzo camera") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            NumberField(
                label = "Porta",
                value = port,
                onValueChange = { text ->
                    port = text
                    text.toIntOrNull()?.let(viewModel::setPort)
                },
                modifier = Modifier.fillMaxWidth(),
                supportingText = "Il controllo della Luna Ultra è su TCP/6666",
            )
            Text(
                text = "La sessione si apre con un frame di handshake UCD2 e si mantiene ripetendolo " +
                    "ogni ${settings.keepAliveSeconds}s.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        GimbalCodeCard(viewModel)

        ProbeCard(viewModel, probe)

        ShapeCard(viewModel)

        SectionCard(
            title = "Notifiche osservate",
            trailing = {
                OutlinedButton(onClick = viewModel::clearSightings) { Text("Azzera") }
            },
        ) {
            Text(
                text = "Muovi il gimbal dallo schermo della camera e guarda quale codice si sveglia. " +
                    "Un codice con molti payload diversi porta numeri che cambiano; uno che ripete " +
                    "sempre gli stessi byte è un battito.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (sightings.isEmpty()) {
                Text("Nessuna notifica ricevuta finora.", style = MaterialTheme.typography.bodyMedium)
            } else {
                sightings.forEach { sighting ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${sighting.code} · ${sighting.name}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (sighting.isNamed) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                            Text(
                                text = "${sighting.count} volte · ${sighting.distinctPayloads} payload distinti",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (!sighting.isNamed) {
                            OutlinedButton(
                                onClick = { viewModel.updateGimbal { it.copy(ptzNotificationCode = sighting.code) } },
                            ) { Text("È il PTZ") }
                        }
                    }
                    if (sighting.lastDump.isNotBlank()) {
                        Text(
                            text = sighting.lastDump,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        GimbalTuningCard(viewModel)

        SectionCard(title = "Invio manuale") {
            var code by remember { mutableStateOf("") }
            var payload by remember { mutableStateOf("") }
            NumberField(
                label = "Codice comando (decimale o 0x…)",
                value = code,
                onValueChange = { code = it },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = payload,
                onValueChange = { payload = it },
                label = { Text("Payload esadecimale (vuoto = nessuno)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { viewModel.sendRaw(code, payload) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Invia") }
        }

        SectionCard(title = "Log") {
            Text(
                text = "Ogni comando inviato e ogni risposta ricevuta, con i byte grezzi e i campi " +
                    "protobuf decodificati. \"Condividi\" lo salva su file e lo allega: è così " +
                    "che va mandato per farlo analizzare.",
                style = MaterialTheme.typography.bodySmall,
            )
            // Il log arriva a migliaia di righe. Disegnarle tutte in una Column significa
            // ricomporre l'intero elenco a ogni riga nuova, e durante una scansione la UI si
            // pianta: la LazyColumn disegna solo ciò che si vede, e la finestra mostra le
            // ultime righe. L'esportazione resta completa.
            val visible = remember(log) { log.takeLast(LOG_WINDOW) }
            if (log.size > LOG_WINDOW) {
                Text(
                    text = "Mostrate le ultime $LOG_WINDOW righe di ${log.size}. " +
                        "L'esportazione le contiene tutte.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = 360.dp),
                reverseLayout = true,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(visible.asReversed()) { entry ->
                    val color = when (entry.level) {
                        LogLevel.ERROR, LogLevel.WARN -> MaterialTheme.colorScheme.error
                        LogLevel.TX -> MaterialTheme.colorScheme.tertiary
                        LogLevel.RX -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                    Column {
                        Text(
                            text = "${entry.time}  ${entry.level}  ${entry.message}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = color,
                        )
                        entry.detail?.takeIf { it.isNotBlank() }?.let { detail ->
                            Text(
                                text = detail,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = color.copy(alpha = 0.7f),
                                modifier = Modifier.padding(start = 12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GimbalCodeCard(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val gimbal = settings.gimbal
    var manual by remember(gimbal.controlCode) {
        mutableStateOf(if (gimbal.controlCode == 0) "" else gimbal.controlCode.toString())
    }

    SectionCard(title = "Comando gimbal") {
        Text(
            text = if (gimbal.isControlCodeKnown) {
                "In uso il codice ${gimbal.controlCode}. Se il gimbal non si muove, non è quello giusto."
            } else {
                "Ancora ignoto. PHONE_COMMAND_GIMBAL_CONTROL esiste con questo nome nell'app " +
                    "Insta360, ma il suo numero non è pubblico: nessuna estrazione lo riporta. " +
                    "Finché resta a 0 l'app non muove il gimbal, invece di sparare byte a caso."
            },
            style = MaterialTheme.typography.bodyMedium,
        )
        NumberField(
            label = "Codice (0 = ignoto)",
            value = manual,
            onValueChange = { text ->
                manual = text
                viewModel.setGimbalControlCode(text.trim().toIntOrNull() ?: 0)
            },
            modifier = Modifier.fillMaxWidth(),
        )
        LabeledValue("Notifica PTZ", gimbal.ptzNotificationCode.toString())
        Text(
            text = "Il valore predefinito ${LunaProtocolCodes.NOTIFICATION_PTZ_STATE_OBSERVED} " +
                "viene da traffico osservato durante il movimento del gimbal, compatibile con " +
                "CAMERA_NOTIFICATION_PTZ_STATE. È un indizio forte, non una certezza.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun ProbeCard(viewModel: MainViewModel, probe: it.persoft.lunaultra.ui.ProbeUiState) {
    SectionCard(title = "Scanner dei codici") {
        Text(
            text = "La camera distingue \"comando inesistente\" da \"argomenti sbagliati\". " +
                "Inviando un corpo vuoto, una risposta \"argomenti sbagliati\" dice che il comando " +
                "c'è e non ha eseguito nulla: è così che si trova un codice senza rischiare di " +
                "farlo partire. Comandi distruttivi e blocco di fabbrica sono esclusi a monte.",
            style = MaterialTheme.typography.bodySmall,
        )

        Button(
            onClick = viewModel::calibrateProbe,
            enabled = !probe.running,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("1. Misura le risposte note") }

        probe.calibration?.let { calibration ->
            LabeledValue("Codice inesistente", calibration.absent)
            LabeledValue("Payload spazzatura", calibration.badPayload)
            LabeledValue("Corpo vuoto su codice reale", calibration.emptyOnReal)
            Text(
                text = calibration.reason,
                style = MaterialTheme.typography.bodySmall,
                color = if (calibration.usable) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        val usable = probe.calibration?.usable == true
        CodeProbe.Range.entries.forEach { range ->
            // Durante una scansione solo la gamma in corso può essere interrotta: le altre
            // restano disabilitate, altrimenti tre pulsanti "Interrompi" identici lasciano
            // credere che ci siano tre scansioni in corso.
            val isRunning = probe.running && probe.range == range
            OutlinedButton(
                onClick = { viewModel.scanRange(range) },
                enabled = isRunning || (usable && !probe.running),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (isRunning) "Interrompi"
                    else "2. Scansiona ${range.label} (${range.from}–${range.to})"
                )
            }
            Text(text = range.note, style = MaterialTheme.typography.bodySmall)
        }

        if (probe.running && probe.total > 0) {
            LinearProgressIndicator(
                progress = { probe.done.toFloat() / probe.total },
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${probe.done} / ${probe.total}", style = MaterialTheme.typography.bodySmall)
        }

        if (probe.hits.isNotEmpty()) {
            HorizontalDivider()
            Text(
                text = "${probe.hits.size} codici hanno risposto diversamente da uno inesistente. " +
                    "In cima quelli che rispondono con dati: esistono di sicuro.",
                style = MaterialTheme.typography.bodySmall,
            )
            probe.hits.sortedByDescending { it.rank }.forEach { hit ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${hit.code}${hit.name?.let { " · $it" } ?: ""}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(text = hit.reply.describe, style = MaterialTheme.typography.bodySmall)
                    }
                    if (hit.rank > 0) {
                        OutlinedButton(onClick = { viewModel.setGimbalControlCode(hit.code) }) {
                            Text("Usa per il gimbal")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Sonda la forma del messaggio di un codice trovato dalla scansione.
 *
 * È l'unico punto dell'app che manda comandi che la camera può eseguire davvero, e per questo
 * chiede un codice esplicito invece di girare da solo su una lista.
 */
@Composable
private fun ShapeCard(viewModel: MainViewModel) {
    val results by viewModel.shape.collectAsState()
    val running by viewModel.shapeRunning.collectAsState()
    var code by remember { mutableStateOf("241") }

    SectionCard(title = "Forma del messaggio") {
        Text(
            text = "Prova un campo alla volta su un codice che la scansione ha trovato. Un corpo " +
                "che smette di essere rifiutato ha indovinato un campo vero.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            text = "Attenzione: ogni forma accettata viene ESEGUITA dalla camera. Falla partire " +
                "guardando la camera, e se il gimbal si muove hai trovato comando e campo.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        NumberField(
            label = "Codice da sondare",
            value = code,
            onValueChange = { code = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { viewModel.probeShape(code) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (running) "Interrompi" else "Prova le forme") }

        results.forEach { result ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = result.label,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = if (result.accepted) "ACCETTATO" else "rifiutato",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result.accepted) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }
}

@Composable
private fun GimbalTuningCard(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val gimbal = settings.gimbal

    SectionCard(title = "Taratura gimbal") {
        Text(
            text = "La forma del messaggio del gimbal non è descritta da nessuna estrazione. " +
                "Questi numeri sono l'ipotesi di partenza — due campi di velocità con segno — " +
                "e si correggono guardando la camera.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Campo pan",
                value = gimbal.panFieldNumber.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { n -> viewModel.updateGimbal { it.copy(panFieldNumber = n) } }
                },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Campo tilt",
                value = gimbal.tiltFieldNumber.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { n -> viewModel.updateGimbal { it.copy(tiltFieldNumber = n) } }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Velocità manuale %",
                value = gimbal.manualSpeedPercent.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { n -> viewModel.updateGimbal { it.copy(manualSpeedPercent = n) } }
                },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Comandi al secondo",
                value = gimbal.commandRateHz.toString(),
                onValueChange = { text ->
                    text.toIntOrNull()?.let { n -> viewModel.updateGimbal { it.copy(commandRateHz = n) } }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(
                label = "Pan max °/s",
                value = gimbal.maxPanSpeedDegPerSec.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxPanSpeedDegPerSec = v) } }
                },
                modifier = Modifier.weight(1f),
            )
            NumberField(
                label = "Tilt max °/s",
                value = gimbal.maxTiltSpeedDegPerSec.toString(),
                onValueChange = { text ->
                    text.toFloatOrNull()?.let { v -> viewModel.updateGimbal { it.copy(maxTiltSpeedDegPerSec = v) } }
                },
                modifier = Modifier.weight(1f),
            )
        }
        Text(
            text = "Le velocità massime servono a convertire la sequenza in tempi di comando: " +
                "misurale cronometrando una rotazione completa e correggile qui.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Inverti pan", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gimbal.invertPan,
                onCheckedChange = { on -> viewModel.updateGimbal { it.copy(invertPan = on) } },
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Inverti tilt", style = MaterialTheme.typography.bodyMedium)
            Switch(
                checked = gimbal.invertTilt,
                onCheckedChange = { on -> viewModel.updateGimbal { it.copy(invertTilt = on) } },
            )
        }
    }
}

/** Righe di log disegnate a schermo. L'esportazione non è limitata. */
private const val LOG_WINDOW = 300
