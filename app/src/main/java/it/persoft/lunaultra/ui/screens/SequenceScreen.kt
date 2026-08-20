package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.timelapse.InterpolationMode
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceScreen(viewModel: MainViewModel) {
    val sequence by viewModel.sequence.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        SectionCard(
            title = "Punti (${sequence.waypoints.size})",
            trailing = {
                OutlinedButton(onClick = viewModel::clearWaypoints, enabled = sequence.waypoints.isNotEmpty()) {
                    Text("Svuota")
                }
            },
        ) {
            if (sequence.waypoints.isEmpty()) {
                Text(
                    "Nessun punto: porta il gimbal sull'inquadratura e premi «Memorizza punto» nella scheda Controllo.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            sequence.waypoints.forEachIndexed { index, waypoint ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        OutlinedTextField(
                            value = waypoint.name,
                            onValueChange = { viewModel.renameWaypoint(waypoint.id, it) },
                            label = { Text("Nome") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { viewModel.moveWaypoint(waypoint.id, -1) }, enabled = index > 0) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Sposta su")
                        }
                        IconButton(
                            onClick = { viewModel.moveWaypoint(waypoint.id, 1) },
                            enabled = index < sequence.waypoints.lastIndex,
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Sposta giù")
                        }
                        IconButton(onClick = { viewModel.removeWaypoint(waypoint.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Elimina")
                        }
                    }
                    LabeledValue("Pan / Tilt", "%.1f° / %.1f°".format(waypoint.pan, waypoint.tilt))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = { viewModel.goToWaypoint(waypoint) }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Text(" Vai")
                        }
                        OutlinedButton(onClick = { viewModel.updateWaypointToCurrent(waypoint.id) }) {
                            Icon(Icons.Filled.Refresh, contentDescription = null)
                            Text(" Aggiorna")
                        }
                        if (!sequence.useTotalDuration && index < sequence.waypoints.lastIndex) {
                            OutlinedTextField(
                                value = waypoint.durationToNextSeconds.roundToInt().toString(),
                                onValueChange = { text ->
                                    text.toFloatOrNull()?.let { viewModel.setWaypointDuration(waypoint.id, it) }
                                },
                                label = { Text("Sec →") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }

        SectionCard(title = "Tempi") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = sequence.useTotalDuration, onCheckedChange = viewModel::setUseTotalDuration)
                Text(
                    if (sequence.useTotalDuration) "Durata totale divisa fra i tratti"
                    else "Durata impostata per singolo tratto",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Durata totale (s)",
                    value = sequence.totalDurationSeconds.roundToInt().toString(),
                    onValueChange = { text -> text.toFloatOrNull()?.let(viewModel::setTotalDuration) },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    label = "Intervallo (s)",
                    value = sequence.intervalSeconds.toString(),
                    onValueChange = { text -> text.toFloatOrNull()?.let(viewModel::setInterval) },
                    keyboardType = KeyboardType.Decimal,
                    modifier = Modifier.weight(1f),
                )
            }
            LabeledValue("Durata effettiva", "${sequence.effectiveTotalSeconds().roundToInt()} s")
            LabeledValue("Scatti stimati", sequence.estimatedShots().toString())
            Text(
                "L'intervallo è indicativo: se la camera non espone il parametro va impostato dal menu della Luna.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        SectionCard(title = "Movimento") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                InterpolationMode.entries.forEach { mode ->
                    FilterChip(
                        selected = sequence.interpolation == mode,
                        onClick = { viewModel.setInterpolation(mode) },
                        label = { Text(mode.label) },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(
                    checked = sequence.configureCameraTimelapse,
                    onCheckedChange = viewModel::setConfigureCameraTimelapse,
                )
                Text("Invia durata e intervallo alla camera", style = MaterialTheme.typography.bodyMedium)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = sequence.controlRecording, onCheckedChange = viewModel::setControlRecording)
                Text("Avvia e ferma la registrazione", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
