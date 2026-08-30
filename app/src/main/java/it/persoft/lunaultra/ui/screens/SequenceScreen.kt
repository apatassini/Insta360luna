package it.persoft.lunaultra.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.timelapse.InterpolationMode
import it.persoft.lunaultra.timelapse.ShootingMode
import it.persoft.lunaultra.timelapse.Waypoint
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.ButtonLabel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.MetricRow
import it.persoft.lunaultra.ui.components.MetricTile
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.SectionCard
import it.persoft.lunaultra.ui.components.ToggleRow
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import it.persoft.lunaultra.ui.viewfinder.CaptureMode
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import it.persoft.lunaultra.ui.components.SliderRow
import it.persoft.lunaultra.timelapse.Interpolation

/** I colori dei punti, a rotazione: due punti vicini non hanno mai lo stesso. */
private val WaypointColors = listOf(Luna.Path, Luna.Pano, Luna.Photo, Luna.PathLapse, Luna.Lapse, Luna.Movie)

private fun waypointColor(index: Int): Color = WaypointColors[index % WaypointColors.size]

/**
 * Il percorso: i punti memorizzati, cosa fare mentre li si percorre e con che tempi.
 *
 * I punti si memorizzano dal mirino, guardando l'inquadratura; qui si mettono in ordine, si
 * correggono e si dà loro una durata. Ognuno ha il suo colore e la sua bussola, perché una lista
 * di gradi non dice in che direzione punta un'inquadratura — un ago sì.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SequenceScreen(viewModel: MainViewModel) {
    val sequence by viewModel.sequence.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val calibration by viewModel.gimbalCalibration.collectAsState()
    val gimbalPosition by viewModel.gimbalPosition.collectAsState()
    val wheel = CaptureMode.forSequence(sequence.mode)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatTile(
                icon = LunaIcons.Flag,
                value = sequence.waypoints.size.toString(),
                label = "punti",
                color = Luna.Path,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = LunaIcons.MotionTimelapse,
                value = "${sequence.effectiveTotalSeconds().roundToInt()} s",
                label = "movimento",
                color = Luna.Lapse,
                modifier = Modifier.weight(1f),
            )
            StatTile(
                icon = wheel.icon,
                value = when (sequence.mode) {
                    ShootingMode.FOTO -> sequence.totalShots().toString()
                    ShootingMode.TIMELAPSE_CAMERA -> sequence.estimatedShots().toString()
                    ShootingMode.VIDEO -> "${sequence.estimatedRecordingSeconds().roundToInt()} s"
                },
                label = when (sequence.mode) {
                    ShootingMode.VIDEO -> "video"
                    else -> "scatti"
                },
                color = wheel.color,
                modifier = Modifier.weight(1f),
            )
        }

        SectionCard(
            title = "Punti (${sequence.waypoints.size})",
            icon = LunaIcons.Flag,
            accent = Luna.Path,
            trailing = {
                TextButton(
                    onClick = viewModel::clearWaypoints,
                    enabled = sequence.waypoints.isNotEmpty(),
                ) {
                    ButtonLabel(LunaIcons.Delete, "Svuota")
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
                if (sequence.hasLegacyWaypoints) {
                    Hint(
                        "I punti della versione precedente non sono abbastanza precisi dopo la " +
                            "correzione degli assi. Portati su ciascuna inquadratura e premi ‘Qui’, " +
                            "oppure svuota la lista e memorizzali di nuovo.",
                    )
                }
                if (sequence.hasUnverifiedManualWaypoints) {
                    Hint(
                        "I punti senza miniatura non possono essere verificati con i punti di " +
                            "controllo. Portati sulla loro inquadratura e premi ‘Qui’.",
                    )
                }
                sequence.waypoints.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        pair.forEach { waypoint ->
                            val index = sequence.waypoints.indexOf(waypoint)
                            WaypointTile(
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
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }

        SectionCard(
            title = "Cosa fa la camera lungo il percorso",
            icon = wheel.icon,
            accent = wheel.color,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    ShootingMode.entries.forEach { mode ->
                        val modeWheel = CaptureMode.forSequence(mode)
                        FilterChip(
                            selected = sequence.mode == mode,
                            onClick = { viewModel.selectSequenceMode(mode) },
                            label = { Text(modeWheel.shortLabel) },
                            leadingIcon = {
                                Icon(modeWheel.icon, contentDescription = null, modifier = Modifier.size(16.dp))
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = modeWheel.color.copy(alpha = 0.20f),
                                selectedLabelColor = modeWheel.color,
                                selectedLeadingIconColor = modeWheel.color,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(sequence.mode.description)
            }
        }

        SectionCard(title = "Panoramica a più scatti", icon = LunaIcons.Panorama, accent = Luna.Pano) {
            Hint(
                "Non sta più qui: una panoramica non ha punti da memorizzare, si descrive con i " +
                    "gradi da coprire e l'obiettivo, e la griglia la calcola l'app. La trovi fra " +
                    "le modalità, toccando il distintivo in cima al mirino.",
            )
        }

        SectionCard(title = "Tempi del percorso", icon = LunaIcons.MotionTimelapse, accent = Luna.Lapse) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ToggleRow(
                    title = if (sequence.useTotalDuration) "Un solo tempo per tutto il percorso"
                    else "Un tempo diverso per ogni tratto",
                    subtitle = if (sequence.useTotalDuration) {
                        "Con 2 punti è il tempo esatto da Punto 1 a Punto 2"
                    } else {
                        "Imposta i secondi da un punto al successivo dentro ogni riquadro"
                    },
                    checked = sequence.useTotalDuration,
                    onCheckedChange = viewModel::setUseTotalDuration,
                )
                if (sequence.useTotalDuration) {
                    NumberField(
                        label = "Tempo di movimento · Punto 1 → ultimo punto (s)",
                        value = sequence.totalDurationSeconds.roundToInt().toString(),
                        onValueChange = { text -> text.toFloatOrNull()?.let(viewModel::setTotalDuration) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (sequence.legCount > 1) {
                        Hint(
                            "I ${sequence.effectiveTotalSeconds().roundToInt()} secondi vengono divisi " +
                                "in ${sequence.legCount} tratti uguali.",
                        )
                    }
                } else {
                    Hint("I secondi di ogni tratto compaiono nei punti sopra: Punto 1 → 2, Punto 2 → 3, ecc.")
                }

                if (sequence.mode.movesContinuously && sequence.controlRecording) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NumberField(
                            label = "Fermo iniziale registrato (s)",
                            value = sequence.startHoldSeconds.toString(),
                            onValueChange = { text -> text.toFloatOrNull()?.let(viewModel::setStartHoldSeconds) },
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                        NumberField(
                            label = "Fermo finale registrato (s)",
                            value = sequence.endHoldSeconds.toString(),
                            onValueChange = { text -> text.toFloatOrNull()?.let(viewModel::setEndHoldSeconds) },
                            keyboardType = KeyboardType.Decimal,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                if (sequence.mode == ShootingMode.TIMELAPSE_CAMERA) {
                    NumberField(
                        label = "Intervallo fra gli scatti della camera (s)",
                        value = sequence.intervalSeconds.toString(),
                        onValueChange = { text -> text.toFloatOrNull()?.let(viewModel::setInterval) },
                        keyboardType = KeyboardType.Decimal,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // I tre numeri che decidono se la sequenza vale la pena, dove si guarda: quanto
                // dura, quanti punti tocca, quanto ne esce. In fondo a un elenco di campi si
                // perdono; come tessere si vedono senza cercarli.
                MetricRow {
                    MetricTile(
                        value = formatSeconds(sequence.effectiveTotalSeconds()),
                        caption = "solo movimento",
                        valueColor = Luna.Lapse,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        value = "${sequence.waypoints.size}",
                        caption = if (sequence.legCount == 1) "punti · 1 tratto" else "punti · ${sequence.legCount} tratti",
                        modifier = Modifier.weight(1f),
                    )
                    when {
                        sequence.mode == ShootingMode.FOTO -> MetricTile(
                            value = "${sequence.totalShots()}",
                            caption = "scatti previsti",
                            valueColor = Luna.Photo,
                            modifier = Modifier.weight(1f),
                        )
                        sequence.mode.movesContinuously && sequence.controlRecording -> MetricTile(
                            value = formatSeconds(sequence.estimatedRecordingSeconds()),
                            caption = "registrazione prevista",
                            valueColor = wheel.color,
                            modifier = Modifier.weight(1f),
                        )
                        else -> MetricTile(
                            value = "${sequence.estimatedShots()}",
                            caption = "scatti dalla camera",
                            valueColor = Luna.Lapse,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                when (sequence.mode) {
                    ShootingMode.TIMELAPSE_CAMERA -> {
                        Hint(
                            "In questa modalità l'intervallo lo usa la camera. Se non accetta il " +
                                "comando va impostato dal suo menu: il log lo dice.",
                        )
                    }

                    ShootingMode.VIDEO -> Unit

                    ShootingMode.FOTO -> Hint(
                        "In modalità foto questa durata è il tempo di movimento fra gli scatti, a cui " +
                            "si aggiungono attesa e scatto.",
                    )
                }
            }
        }

        if (sequence.mode.movesContinuously && sequence.controlRecording && sequence.waypoints.size >= 2) {
            SectionCard(title = "Cosa succede quando premi Registra", icon = LunaIcons.Video, accent = wheel.color) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Hint("1. La registrazione è ancora ferma e il gimbal torna al Punto 1.")
                    Hint("2. Sul Punto 1 parte la registrazione e resta fermo ${sequence.startHoldSeconds} s.")
                    Hint(
                        "3. Si muove dal Punto 1 al Punto ${sequence.waypoints.size} in " +
                            "${sequence.effectiveTotalSeconds().roundToInt()} s, rispettando l'ordine dei punti.",
                    )
                    Hint(
                        "4. Sul punto finale resta fermo ${sequence.endHoldSeconds} s e poi ferma la registrazione.",
                    )
                }
            }
        }

        SectionCard(title = "Movimento", icon = LunaIcons.Joystick, accent = Luna.PathLapse) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    InterpolationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = sequence.interpolation == mode,
                            onClick = { viewModel.setInterpolation(mode) },
                            label = { Text(mode.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Luna.PathLapse.copy(alpha = 0.20f),
                                selectedLabelColor = Luna.PathLapse,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Hint(
                    "Lineare mantiene la stessa velocità per tutto il tratto; morbida parte e " +
                        "arriva piano, che su un movimento lungo si nota.",
                )
                if (sequence.interpolation == InterpolationMode.SMOOTH) {
                    // Il tempo perso a partire e a fermarsi va recuperato da qualche parte, e
                    // viene recuperato in mezzo: questo cursore dice quanto. È il numero che si
                    // vede guardando il video, quindi si imposta quello e non la rampa.
                    SliderRow(
                        label = "Punta al centro",
                        value = sequence.easingPeak,
                        valueRange = Interpolation.PUNTA_MINIMA..Interpolation.PUNTA_MASSIMA,
                        steps = 9,
                        onValueChange = viewModel::setEasingPeak,
                        valueLabel = "%.1f× la media".format(sequence.easingPeak),
                    )
                    Hint(
                        when {
                            sequence.easingPeak <= 1.05f ->
                                "A uno è la retta: velocità costante, ma partenza e arresto di colpo."
                            sequence.easingPeak >= 1.45f ->
                                "A metà tratto la camera va %.0f%% più della media: su un movimento lungo si vede."
                                    .format((sequence.easingPeak - 1f) * 100f)
                            else ->
                                "Partenza e arresto morbidi, e a metà tratto solo il %.0f%% più della media."
                                    .format((sequence.easingPeak - 1f) * 100f)
                        },
                    )
                }
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

/** Numero grande con la sua etichetta: il riepilogo si legge da lontano, non si conta a mano. */
@Composable
private fun StatTile(
    icon: ImageVector,
    value: String,
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), shape)
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(vertical = 12.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(text = value, style = MaterialTheme.typography.titleMedium, color = color, maxLines = 1)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Luna.OnSurfaceDim,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

/**
 * Un punto memorizzato.
 *
 * La bussola disegna dove guarda: l'ago è il pan, la barra sotto l'inclinazione. Su una
 * panoramica di otto punti è l'unico modo di accorgersi che due inquadrature si sovrappongono
 * senza andare a rileggere otto coppie di numeri.
 */
@Composable
private fun WaypointTile(
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
    modifier: Modifier = Modifier,
) {
    val color = waypointColor(index)
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .background(Luna.SurfaceHigh, shape)
            .border(1.dp, color.copy(alpha = 0.35f), shape)
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier.size(28.dp).background(color.copy(alpha = 0.20f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.labelLarge,
                    color = color,
                )
            }
            Text(
                text = when {
                    index == 0 -> "partenza"
                    last -> "arrivo"
                    else -> "punto ${index + 1} → ${index + 2}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = Luna.OnSurfaceDim,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }

        val previewBitmap = remember(waypoint.previewJpegBase64) {
            waypoint.previewJpeg()?.let { bytes -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }
        }
        if (previewBitmap != null) {
            Image(
                bitmap = previewBitmap.asImageBitmap(),
                contentDescription = "Inquadratura salvata per ${waypoint.name}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.CenterHorizontally).size(84.dp),
            )
        } else {
            WaypointCompass(
                pan = waypoint.pan,
                tilt = waypoint.tilt,
                color = color,
                modifier = Modifier.align(Alignment.CenterHorizontally).size(84.dp),
            )
        }

        Text(
            text = "%.1f°  /  %.1f°".format(waypoint.pan, waypoint.tilt),
            style = MaterialTheme.typography.labelMedium,
            color = Luna.OnSurfaceDim,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )

        if (waypoint.needsRecapture) {
            Text(
                text = "da rimemorizzare",
                style = MaterialTheme.typography.labelSmall,
                color = Luna.Rec,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        } else if (waypoint.previewJpegBase64 == null) {
            Text(
                text = "senza foto di controllo",
                style = MaterialTheme.typography.labelSmall,
                color = Luna.Rec,
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )
        }

        OutlinedTextField(
            value = waypoint.name,
            onValueChange = onRename,
            label = { Text("Nome") },
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
        )

        if (showLegDuration) {
            OutlinedTextField(
                value = waypoint.durationToNextSeconds.roundToInt().toString(),
                onValueChange = { text -> text.toFloatOrNull()?.let(onDuration) },
                label = { Text("Tempo ${index + 1} → ${index + 2} (s)") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TileAction(icon = LunaIcons.Play, label = "Vai", color = color, onClick = onGoTo, modifier = Modifier.weight(1f))
            TileAction(
                icon = LunaIcons.Refresh,
                label = "Qui",
                color = Luna.Accent,
                onClick = onUpdate,
                modifier = Modifier.weight(1f),
            )
        }

        // Ordine ed eliminazione su una riga a parte: in una casella stretta tre icone accanto
        // al numero si toccano fra loro, e quella che si preme per sbaglio cancella il punto.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            TileIcon(
                icon = LunaIcons.Up,
                description = "Sposta prima",
                color = Luna.OnSurfaceDim,
                enabled = index > 0,
                onClick = { onMove(-1) },
                modifier = Modifier.weight(1f),
            )
            TileIcon(
                icon = LunaIcons.Down,
                description = "Sposta dopo",
                color = Luna.OnSurfaceDim,
                enabled = !last,
                onClick = { onMove(1) },
                modifier = Modifier.weight(1f),
            )
            TileIcon(
                icon = LunaIcons.Delete,
                description = "Elimina il punto",
                color = Luna.Rec,
                enabled = true,
                onClick = onRemove,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TileAction(
    icon: ImageVector,
    label: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.12f), shape)
            .height(34.dp)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(15.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

@Composable
private fun TileIcon(
    icon: ImageVector,
    description: String,
    color: Color,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(12.dp)
    val tint = if (enabled) color else color.copy(alpha = 0.3f)
    Box(
        modifier = modifier
            .height(32.dp)
            .background(tint.copy(alpha = 0.10f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
    }
}

/** Bussola del punto: ago sul pan, barra sull'inclinazione. */
@Composable
private fun WaypointCompass(pan: Float, tilt: Float, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val radius = size.minDimension / 2f - 2.dp.toPx()
        val center = Offset(size.width / 2f, size.height / 2f)

        drawCircle(color = Luna.Bg, radius = radius, center = center)
        drawCircle(
            color = color.copy(alpha = 0.35f),
            radius = radius,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
        for (step in 0 until 12) {
            val angle = Math.toRadians(step * 30.0)
            val outer = Offset(
                center.x + (radius * sin(angle)).toFloat(),
                center.y - (radius * cos(angle)).toFloat(),
            )
            val inner = Offset(
                center.x + (radius * 0.86f * sin(angle)).toFloat(),
                center.y - (radius * 0.86f * cos(angle)).toFloat(),
            )
            drawLine(color = Luna.OnSurfaceDim.copy(alpha = 0.4f), start = inner, end = outer, strokeWidth = 1.dp.toPx())
        }

        val panRad = Math.toRadians(pan.toDouble())
        val needle = Offset(
            center.x + (radius * 0.74f * sin(panRad)).toFloat(),
            center.y - (radius * 0.74f * cos(panRad)).toFloat(),
        )
        drawLine(color = color, start = center, end = needle, strokeWidth = 3.dp.toPx())
        drawCircle(color = color, radius = 3.dp.toPx(), center = center)

        // L'inclinazione come barra orizzontale: sopra il centro guarda in alto, sotto in basso.
        val tiltFraction = (tilt / 90f).coerceIn(-1f, 1f)
        val barY = center.y - radius * 0.62f * tiltFraction
        drawLine(
            color = color.copy(alpha = 0.55f),
            start = Offset(center.x - radius * 0.55f, barY),
            end = Offset(center.x + radius * 0.55f, barY),
            strokeWidth = 2.dp.toPx(),
        )
    }
}

/** Secondi in una forma leggibile: sotto il minuto i secondi, sopra minuti e secondi. */
private fun formatSeconds(seconds: Float): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    return if (total < 60) "${total}s" else "%d:%02d".format(total / 60, total % 60)
}
