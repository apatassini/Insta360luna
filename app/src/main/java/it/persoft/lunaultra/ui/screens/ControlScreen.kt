package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.timelapse.RunPhase
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.GimbalPad
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard
import it.persoft.lunaultra.ui.italianLabel
import kotlin.math.roundToInt

@Composable
fun ControlScreen(viewModel: MainViewModel) {
    val settings by viewModel.settings.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val status by viewModel.status.collectAsState()
    val ptz by viewModel.ptz.collectAsState()
    val run by viewModel.runState.collectAsState()
    val sequence by viewModel.sequence.collectAsState()
    val connected = connection == ConnectionState.CONNECTED

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionCard(title = "Connessione") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Indirizzo camera",
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
            LabeledValue("Stato", connection.italianLabel())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { if (connected) viewModel.disconnect() else viewModel.connect() },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(if (connected) "Disconnetti" else "Connetti")
                }
                OutlinedButton(
                    onClick = viewModel::refreshStatus,
                    enabled = connected,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Aggiorna stato")
                }
            }
        }

        SectionCard(title = "Stato camera") {
            LabeledValue("Batteria", status.batteryPercent?.let { "$it%" } ?: "—")
            LabeledValue("Registrazione", status.recording?.let { if (it) "in corso" else "ferma" } ?: "—")
            LabeledValue("Modalità", status.captureMode ?: "—")
            LabeledValue("Modello", status.model ?: "—")
            if (!status.hasData && connected) {
                Text(
                    text = "Nessun dato decodificato: configura gli id comando e i campi di stato in Diagnostica.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::selectTimelapseMode, enabled = connected, modifier = Modifier.weight(1f)) {
                    Text("Modalità TL")
                }
                OutlinedButton(onClick = viewModel::startRecording, enabled = connected, modifier = Modifier.weight(1f)) {
                    Text("REC start")
                }
                OutlinedButton(onClick = viewModel::stopRecording, enabled = connected, modifier = Modifier.weight(1f)) {
                    Text("REC stop")
                }
            }
        }

        SectionCard(title = "Gimbal") {
            LabeledValue(
                "Posizione (pan / tilt)",
                "%.1f° / %.1f°%s".format(ptz.pan, ptz.tilt, if (ptz.fromCamera) "" else " (stima)"),
            )
            Text("Velocità manuale: ${settings.gimbal.manualSpeedPercent}%", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = settings.gimbal.manualSpeedPercent.toFloat(),
                onValueChange = { v -> viewModel.updateGimbal { it.copy(manualSpeedPercent = v.roundToInt().coerceIn(1, 100)) } },
                valueRange = 1f..100f,
            )
            GimbalPad(
                enabled = connected,
                onJog = viewModel::jogStart,
                onRelease = viewModel::jogStop,
                onStop = viewModel::jogStop,
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = viewModel::zeroPosition, modifier = Modifier.weight(1f)) {
                    Text("Azzera posizione")
                }
                Button(onClick = viewModel::captureWaypoint, modifier = Modifier.weight(1f)) {
                    Text("Memorizza punto")
                }
            }
        }

        SectionCard(title = "Esecuzione") {
            LabeledValue("Punti memorizzati", sequence.waypoints.size.toString())
            LabeledValue("Durata sequenza", "${sequence.effectiveTotalSeconds().roundToInt()} s")
            LabeledValue("Fase", run.phase.italianLabel())
            run.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (run.running || run.phase == RunPhase.COMPLETED) {
                LinearProgressIndicator(
                    progress = { run.overallProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Text(
                    text = "Tratto ${run.legIndex + 1}/${run.legCount.coerceAtLeast(1)} · " +
                        "target %.1f° / %.1f°".format(run.targetPan, run.targetTilt),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(
                onClick = viewModel::startRun,
                enabled = connected && sequence.isRunnable && !run.running,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("AVVIA TIMELAPSE")
            }
            Button(
                onClick = viewModel::emergencyStop,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("STOP", textAlign = TextAlign.Center)
            }
        }
    }
}

fun RunPhase.italianLabel(): String = when (this) {
    RunPhase.IDLE -> "In attesa"
    RunPhase.PREPARING -> "Preparazione"
    RunPhase.RUNNING -> "In esecuzione"
    RunPhase.STOPPING -> "Arresto"
    RunPhase.COMPLETED -> "Completata"
    RunPhase.ABORTED -> "Interrotta"
}
