package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.timelapse.InterpolationMode
import it.persoft.lunaultra.timelapse.ShootingMode
import it.persoft.lunaultra.timelapse.Waypoint
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard
import it.persoft.lunaultra.ui.components.ToggleRow
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import it.persoft.lunaultra.ui.viewfinder.CaptureMode
import kotlin.math.roundToInt

/**
 * Il percorso: i punti memorizzati, cosa fare mentre li si percorre e con che tempi.
 *
 * I punti si memorizzano dal mirino, guardando l'inquadratura; qui si mettono in ordine, si
 * correggono e si dà loro una durata. Sono due momenti diversi del lavoro e stanno in due posti
 * diversi apposta.
 */
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
            icon = LunaIcons.Flag,
            trailing = {
                TextButton(
                    onClick = viewModel::clearWaypoints,
                    enabled = sequence.waypoints.isNotEmpty(),
                ) {
                    Icon(LunaIcons.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("  Svuota")
                }
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (sequence.waypoints.isEmpty()) {
                    Hint(
                        "Nessun punto. Nel mirino porta il gimbal sull'inquadratura che vuoi e " +
                            "premi il tasto con la bandierina: il punto viene memorizzato lì.",
                    )
                }
                sequence.waypoints.forEachIndexed { index, waypoint ->
                    WaypointCard(
                        waypoint = waypoint,
                        index = index,
                        last = index == sequence.waypoints.lastIndex,
                        showLegDuration = !sequence.useTotalDuration && index < sequence.waypoints.lastIndex,
                        onRename = { viewModel.renameWaypoint(waypoint.id, it) },
                        onMove = { delta -> viewModel.moveWaypoint(waypoint.id, delta) },
                        onRemove = { viewModel.removeWaypoint(waypoint.id) },
                        onGoTo = { viewModel.goToWaypoint(waypoint) },
                        onUpdate = { viewModel.updateWaypointToCurrent(waypoint.id) },
                        onDuration = { viewModel.setWaypointDuration(waypoint.id, it) },
                    )
                }
            }
        }

        SectionCard(title = "Cosa fa la camera lungo il percorso", icon = LunaIcons.MotionVideo) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ShootingMode.entries.forEach { mode ->
                        val wheel = CaptureMode.forSequence(mode)
                        FilterChip(
                            selected = sequence.mode == mode,
                            onClick = { viewModel.selectSequenceMode(mode) },
                            label = { Text(wheel.shortLabel) },
                            leadingIcon = {
                                Icon(wheel.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.AccentDim,
                                selectedLabelColor = Luna.OnSurface,
                                selectedLeadingIconColor = Luna.Accent,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(sequence.mode.description)
            }
        }

        if (sequence.mode == ShootingMode.FOTO) {
            SectionCard(title = "Panorama a scatti", icon = LunaIcons.Panorama) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(
                            label = "Scatti per tratto",
                            value = sequence.shotsPerLeg.toString(),
                            onValueChange = { text -> text.toIntOrNull()?.let(viewModel::setShotsPerLeg) },
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            label = "Attesa prima dello scatto (s)",
                            value = sequence.settleSeconds.toString(),
                            onValueChange = { text -> text.toFloatOrNull()?.let(viewModel::setSettleSeconds) },
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LabeledValue("Scatti totali", sequence.totalShots().toString())
                    LabeledValue("Durata stimata", "${sequence.estimatedPhotoSeconds().roundToInt()} s")
                    Hint(
                        "L'attesa serve a far esaurire l'inerzia del gimbal: scattare subito dopo " +
                            "un movimento produce foto mosse, e in una panoramica il difetto si vede " +
                            "proprio sulle giunzioni. Sotto il secondo è raramente sufficiente.",
                    )
                    Hint(
                        "Il punto finale di un tratto coincide con l'iniziale del successivo e viene " +
                            "scattato una volta sola.",
                    )
                }
            }
        }

        SectionCard(title = "Tempi", icon = LunaIcons.MotionTimelapse) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow(
                    title = if (sequence.useTotalDuration) "Durata totale divisa fra i tratti"
                    else "Durata impostata tratto per tratto",
                    subtitle = "Con la durata totale ogni tratto dura uguale, qualunque sia la sua ampiezza",
                    checked = sequence.useTotalDuration,
                    onCheckedChange = viewModel::setUseTotalDuration,
                )
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
                when (sequence.mode) {
                    ShootingMode.TIMELAPSE_CAMERA -> {
                        LabeledValue("Scatti stimati dalla camera", sequence.estimatedShots().toString())
                        Hint(
                            "In questa modalità l'intervallo lo usa la camera. Se non accetta il " +
                                "comando va impostato dal suo menu: il log lo dice.",
                        )
                    }

                    ShootingMode.VIDEO -> Hint(
                        "In modalità video l'intervallo non viene usato: la durata è tempo reale di ripresa.",
                    )

                    ShootingMode.FOTO -> Hint(
                        "In modalità foto questa durata è il tempo di movimento fra gli scatti, a cui " +
                            "si aggiungono attesa e scatto.",
                    )
                }
            }
        }

        SectionCard(title = "Movimento", icon = LunaIcons.Joystick) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    InterpolationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = sequence.interpolation == mode,
                            onClick = { viewModel.setInterpolation(mode) },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.AccentDim,
                                selectedLabelColor = Luna.OnSurface,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "Lineare mantiene la stessa velocità per tutto il tratto; smooth parte e " +
                        "arriva piano, che su un movimento lungo si nota.",
                )
                if (sequence.mode == ShootingMode.TIMELAPSE_CAMERA) {
                    ToggleRow(
                        title = "Invia durata e intervallo alla camera",
                        checked = sequence.configureCameraTimelapse,
                        onCheckedChange = viewModel::setConfigureCameraTimelapse,
                        icon = LunaIcons.Timelapse,
                    )
                }
                if (sequence.mode.movesContinuously) {
                    ToggleRow(
                        title = "Avvia e ferma la registrazione",
                        subtitle = "Spento, il gimbal si muove ma la camera non riprende",
                        checked = sequence.controlRecording,
                        onCheckedChange = viewModel::setControlRecording,
                        icon = LunaIcons.Video,
                    )
                }
            }
        }
    }
}

@Composable
private fun WaypointCard(
    waypoint: Waypoint,
    index: Int,
    last: Boolean,
    showLegDuration: Boolean,
    onRename: (String) -> Unit,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
    onGoTo: () -> Unit,
    onUpdate: () -> Unit,
    onDuration: (Float) -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Luna.SurfaceHigh, shape)
            .border(1.dp, Luna.GlassBorder, shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Luna.Accent.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = Luna.Accent,
                )
            }
            OutlinedTextField(
                value = waypoint.name,
                onValueChange = onRename,
                label = { Text("Nome") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onMove(-1) }, enabled = index > 0) {
                Icon(LunaIcons.Up, contentDescription = "Sposta su")
            }
            IconButton(onClick = { onMove(1) }, enabled = !last) {
                Icon(LunaIcons.Down, contentDescription = "Sposta giù")
            }
            IconButton(onClick = onRemove) {
                Icon(LunaIcons.Delete, contentDescription = "Elimina", tint = Luna.Rec)
            }
        }

        LabeledValue("Pan / Tilt", "%.1f° / %.1f°".format(waypoint.pan, waypoint.tilt))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onGoTo) {
                Icon(LunaIcons.Play, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  Vai")
            }
            OutlinedButton(onClick = onUpdate) {
                Icon(LunaIcons.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("  Aggiorna")
            }
            if (showLegDuration) {
                OutlinedTextField(
                    value = waypoint.durationToNextSeconds.roundToInt().toString(),
                    onValueChange = { text -> text.toFloatOrNull()?.let(onDuration) },
                    label = { Text("Sec →") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
