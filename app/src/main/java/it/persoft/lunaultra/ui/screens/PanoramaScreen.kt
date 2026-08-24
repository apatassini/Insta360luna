package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.stitch.StitchUiState
import it.persoft.lunaultra.stitch.sphericalCoverage
import it.persoft.lunaultra.timelapse.LunaOptics
import it.persoft.lunaultra.timelapse.PanoramaPlan
import it.persoft.lunaultra.timelapse.TimelapseSequence
import it.persoft.lunaultra.timelapse.PanoramaPreset
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.ButtonLabel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.MetricRow
import it.persoft.lunaultra.ui.components.StatusChip
import it.persoft.lunaultra.ui.components.MetricTile
import it.persoft.lunaultra.ui.components.NumberField
import it.persoft.lunaultra.ui.components.ToggleRow
import it.persoft.lunaultra.ui.components.SectionCard
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * La panoramica a più scatti, e nient'altro.
 *
 * Prima questa funzione viveva dentro «Automazioni gimbal», in mezzo a punti memorizzati,
 * interpolazioni e durate. Ma una panoramica non ha punti da memorizzare: la si descrive con
 * due cose — quanti gradi coprire e con che obiettivo — e la griglia degli scatti la calcola
 * l'app. I waypoint esistono, ma sono il *come*, non il *cosa*, e non hanno motivo di stare
 * sotto gli occhi di chi vuole solo scattare.
 */
@Composable
fun PanoramaScreen(viewModel: MainViewModel) {
    val sequence by viewModel.sequence.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val calibration by viewModel.gimbalCalibration.collectAsState()
    val plan by viewModel.panoramaPlan.collectAsState()
    val connection by viewModel.connectionState.collectAsState()
    val run by viewModel.runState.collectAsState()
    val stitch by viewModel.stitchState.collectAsState()
    val connected = connection == ConnectionState.CONNECTED

    // Il piano si rifà a ogni modifica: il numero di scatti è la cosa che decide se ne vale
    // la pena, e va saputo prima di cominciare.
    LaunchedEffect(
        sequence.panoramaHorizontalDegrees,
        sequence.panoramaVerticalDegrees,
        sequence.panoramaOverlapPercent,
        settings.photo.zoomScale,
        calibration,
    ) { viewModel.refreshPanoramaPreview() }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

        if (!calibration.isValid) {
            SectionCard(title = "Serve la calibrazione", icon = LunaIcons.Warning, accent = Luna.Warn) {
                Hint(
                    "La griglia degli scatti nasce dai fine corsa misurati: senza, l'app non sa " +
                        "se la copertura che chiedi ci sta. Fai una calibrazione completa da " +
                        "Impostazioni, poi torna qui.",
                )
            }
        }

        SectionCard(title = "Scatto sferico", icon = LunaIcons.Center, accent = Luna.Multi) {
            ToggleRow(
                title = "Prendi tutto quello che il gimbal raggiunge",
                subtitle = "I gradi non li scegli tu: li detta la corsa misurata dalla " +
                    "calibrazione. Sovrapposizione fissa al 20%, e il buco sotto viene chiuso.",
                checked = sequence.panoramaSpherical,
                onCheckedChange = viewModel::setPanoramaSpherical,
            )
            if (sequence.panoramaSpherical && calibration.isValid) {
                val fov = LunaOptics.fieldOfView(settings.photo.zoomScale, sequence.panoramaAspect)
                val coverage = sphericalCoverage(
                    panMinimumDeg = calibration.panLimits.minimumDeg,
                    panMaximumDeg = calibration.panLimits.maximumDeg,
                    tiltMinimumDeg = calibration.tiltLimits.minimumDeg,
                    tiltMaximumDeg = calibration.tiltLimits.maximumDeg,
                    horizontalFovDegrees = fov.horizontalDegrees,
                    verticalFovDegrees = fov.verticalDegrees,
                )
                MetricRow {
                    MetricTile(
                        value = "%.0f°".format(coverage.horizontalDegrees.coerceAtMost(360f)),
                        caption = "attorno a te",
                        valueColor = Luna.Multi,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        value = "%.0f°".format(coverage.verticalDegrees.coerceAtMost(180f)),
                        caption = "dall'alto in basso",
                        valueColor = Luna.Multi,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        value = if (coverage.closesTheCircle) "chiuso" else "%.0f°".format(coverage.missingHorizontalDegrees),
                        caption = if (coverage.closesTheCircle) "il giro si chiude" else "che restano dietro",
                        valueColor = if (coverage.closesTheCircle) Luna.Ok else Luna.Warn,
                        modifier = Modifier.weight(1f),
                    )
                }
                Hint(
                    if (coverage.closesTheCircle) {
                        "Il giro si chiude davvero. La corsa del pan è " +
                            "${"%.0f".format(calibration.panLimits.spanDeg)}° su 360, ma un fotogramma non è una linea: " +
                            "il primo scatto vede mezzo campo prima del suo centro e l'ultimo " +
                            "mezzo campo dopo, e a questo zoom quel mezzo campo basta a coprire " +
                            "il resto. Da 2× in su il campo si stringe e resta un settore fuori."
                    } else {
                        "A questo zoom il giro non si chiude: il campo dell'obiettivo è troppo " +
                            "stretto per coprire quello che la corsa non raggiunge. Con lo zoom " +
                            "a 1× i ${"%.0f".format(calibration.panLimits.spanDeg)}° di corsa più il campo chiudono il cerchio."
                    },
                )
                Hint(
                    "Sotto, dove il tilt finisce la corsa, il buco viene chiuso estendendo " +
                        "l'ultimo anello buono: quei pixel sono inventati, non fotografati, e " +
                        "il log dice quante righe sono.",
                )
            } else if (sequence.panoramaSpherical) {
                Hint("Serve la calibrazione: i gradi dello scatto sferico vengono dai fine corsa misurati.")
            }
        }

        SectionCard(
            title = "Quanto coprire",
            icon = LunaIcons.Panorama,
            accent = Luna.Pano,
            trailing = {
                if (sequence.panoramaSpherical) StatusChip("decisa dalla sferica", Luna.OnSurfaceDim)
            },
        ) {
            val active = PanoramaPreset.matching(
                sequence.panoramaHorizontalDegrees,
                sequence.panoramaVerticalDegrees,
            )
            PanoramaPreset.entries.forEach { preset ->
                val selected = preset == active
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Luna.Pano.copy(alpha = 0.16f) else Color.Transparent)
                        .clickable { viewModel.setPanoramaPreset(preset) }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = preset.label,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (selected) Luna.Pano else Color.White,
                    )
                    Text(
                        text = preset.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = Luna.OnSurfaceDim,
                    )
                }
            }
            Text(
                text = if (active == null) "PERSONALIZZATA" else "oppure a mano",
                style = MaterialTheme.typography.labelMedium,
                color = if (active == null) Luna.Pano else Luna.OnSurfaceDim,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                NumberField(
                    label = "Orizzontale",
                    value = sequence.panoramaHorizontalDegrees.roundToInt().toString(),
                    onValueChange = { t -> t.toFloatOrNull()?.let(viewModel::setPanoramaHorizontalDegrees) },
                    modifier = Modifier.weight(1f),
                    supportingText = "gradi",
                )
                NumberField(
                    label = "Verticale",
                    value = sequence.panoramaVerticalDegrees.roundToInt().toString(),
                    onValueChange = { t -> t.toFloatOrNull()?.let(viewModel::setPanoramaVerticalDegrees) },
                    modifier = Modifier.weight(1f),
                    supportingText = "gradi",
                )
            }
        }

        SectionCard(title = "Con che obiettivo", icon = LunaIcons.Photo, accent = Luna.Photo) {
            Hint(
                "Più zoom, più stretto il campo visivo: la stessa copertura richiede più scatti, " +
                    "e ogni scatto ha più dettaglio.",
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                LunaOptics.zoomStops.forEach { zoom ->
                    FilterChip(
                        selected = settings.photo.zoomScale == zoom,
                        onClick = { viewModel.setPhotoZoom(zoom) },
                        label = { Text("${zoom}×") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Luna.Photo.copy(alpha = 0.20f),
                            selectedLabelColor = Luna.Photo,
                        ),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val fov = LunaOptics.fieldOfView(settings.photo.zoomScale, sequence.panoramaAspect)
            LabeledValue("Campo visivo", "%.0f° × %.0f° · %s".format(fov.horizontalDegrees, fov.verticalDegrees, fov.lensLabel))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                Text("Sovrapposizione", style = MaterialTheme.typography.labelSmall, color = Luna.OnSurfaceDim)
                listOf(20, 30, 40).forEach { percent ->
                    FilterChip(
                        selected = sequence.panoramaOverlapPercent == percent,
                        onClick = { viewModel.setPanoramaOverlap(percent) },
                        label = { Text("$percent%") },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        SectionCard(title = "Cosa succederà", icon = LunaIcons.Sequence, accent = Luna.Ok) {
            val current = plan
            if (current == null) {
                Hint(
                    if (calibration.isValid) {
                        "Questa copertura non entra nei fine corsa misurati: riduci i gradi, " +
                            "oppure ricentra la camera prima di riprovare."
                    } else {
                        "Il piano si può calcolare solo dopo la calibrazione."
                    },
                )
            } else {
                val realHorizontal = current.horizontalCenterSpan + current.fieldOfView.horizontalDegrees
                val realVertical = current.verticalCenterSpan + current.fieldOfView.verticalDegrees
                MetricRow {
                    MetricTile(
                        value = current.totalShots.toString(),
                        caption = "scatti",
                        valueColor = Luna.Pano,
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        value = current.columnsPerRow.joinToString("·"),
                        caption = "scatti per fila",
                        modifier = Modifier.weight(1f),
                    )
                    MetricTile(
                        value = panoramaDuration(current, calibration, sequence.settleSeconds),
                        caption = "durata stimata",
                        modifier = Modifier.weight(1f),
                    )
                }
                // La griglia disegnata dice in un colpo d'occhio quello che tre numeri
                // lasciano immaginare: la forma della panoramica e quanto si sovrappongono i
                // fotogrammi. Una sovrapposizione troppo magra si vede prima di scattare.
                PanoramaGridPreview(
                    columnsPerRow = current.columnsPerRow,
                    rows = current.rows,
                    frameWidthFraction = (current.fieldOfView.horizontalDegrees / realHorizontal)
                        .coerceIn(0.05f, 1f),
                    frameHeightFraction = (current.fieldOfView.verticalDegrees / realVertical)
                        .coerceIn(0.05f, 1f),
                )
                LabeledValue("Copertura reale", "%.0f° × %.0f°".format(realHorizontal, realVertical))
                current.warning?.let { Hint(it) }
                Button(
                    onClick = viewModel::shootPanorama,
                    enabled = connected && !run.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    ButtonLabel(LunaIcons.Panorama, "Scatta ${current.totalShots} foto")
                }
                Hint(
                    "Parte dall'inquadratura attuale, che diventa il centro della panoramica. " +
                        "La camera percorre la griglia a serpentina e scatta a ogni posizione, " +
                        "fermandosi il tempo necessario perché l'immagine non venga mossa.",
                )
            }
        }

        SectionCard(title = "Unione delle foto", icon = LunaIcons.Panorama, accent = Luna.Ok) {
            ToggleRow(
                title = "Unisci da sola alla fine",
                subtitle = "Scarica gli scatti, li rimette sulla sfera secondo l'obiettivo, " +
                    "sfuma le giunzioni e salva la panoramica nella galleria del telefono.",
                checked = sequence.autoStitchPanorama,
                onCheckedChange = viewModel::setAutoStitchPanorama,
            )
            when (val state = stitch) {
                is StitchUiState.Idle -> Hint(
                    "Le foto singole restano sulla camera: l'unione lavora su copie scaricate " +
                        "e non tocca niente sulla scheda.",
                )

                is StitchUiState.Working -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(state.message, style = MaterialTheme.typography.bodyMedium)
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Ci vuole qualche minuto: lo scaricamento passa dal Wi-Fi della camera.")
                }

                is StitchUiState.Done -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MetricRow {
                        MetricTile(
                            value = "${state.report.canvasWidth}×${state.report.canvasHeight}",
                            caption = "pixel della panoramica",
                            valueColor = Luna.Ok,
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            value = "%.0f°×%.0f°".format(
                                state.report.coverageHorizontalDegrees,
                                state.report.coverageVerticalDegrees,
                            ),
                            caption = "copertura unita",
                            modifier = Modifier.weight(1f),
                        )
                        MetricTile(
                            value = "%.1f°".format(state.report.worstCorrectionDegrees),
                            caption = "correzione massima",
                            valueColor = if (state.report.worstCorrectionDegrees > 2f) Luna.Warn else Luna.Ok,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    LabeledValue("Salvata come", state.fileName, valueColor = Luna.Ok)
                    Hint("La trovi in DCIM › Luna Ultra, nella galleria del telefono.")
                }

                is StitchUiState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "UNIONE NON RIUSCITA",
                        style = MaterialTheme.typography.labelMedium,
                        color = Luna.Rec,
                    )
                    Text(state.reason, style = MaterialTheme.typography.bodySmall)
                    Hint("Gli scatti sono comunque sulla camera: si possono unire con un altro programma.")
                }
            }
        }
    }
}

/**
 * La griglia degli scatti disegnata: i fotogrammi al loro posto, con la loro sovrapposizione.
 *
 * Tre numeri — colonne, righe, gradi — lasciano immaginare la forma. Il disegno la mostra: se
 * la panoramica viene larga e bassa si vede, e si vede anche quanto si accavallano i
 * fotogrammi, che è la cosa che decide se l'unione riuscirà. Una sovrapposizione troppo magra
 * si riconosce a occhio prima di scattare, non dopo aver guardato le foto.
 */
@Composable
private fun PanoramaGridPreview(
    columnsPerRow: List<Int>,
    rows: Int,
    frameWidthFraction: Float,
    frameHeightFraction: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(12.dp))
            .background(Luna.Bg),
    ) {
        val frameWidth = size.width * frameWidthFraction
        val frameHeight = size.height * frameHeightFraction
        // I centri sono equidistanti fra il primo e l'ultimo fotogramma, come nel piano vero.
        val spanX = size.width - frameWidth
        val spanY = size.height - frameHeight
        for (row in 0 until rows) {
            val top = if (rows <= 1) spanY / 2f else spanY * row / (rows - 1)
            // Le file alte hanno meno fotogrammi, e più larghi in gradi di pan: il disegno lo
            // mostra com'è, perché è la differenza fra una griglia e quello che si scatta.
            val columns = columnsPerRow.getOrElse(row) { 1 }
            for (column in 0 until columns) {
                val left = if (columns <= 1) spanX / 2f else spanX * column / (columns - 1)
                drawRect(
                    color = Luna.Pano.copy(alpha = 0.12f),
                    topLeft = Offset(left, top),
                    size = Size(frameWidth, frameHeight),
                )
                drawRect(
                    color = Luna.Pano.copy(alpha = 0.55f),
                    topLeft = Offset(left, top),
                    size = Size(frameWidth, frameHeight),
                    style = Stroke(width = 1.5f),
                )
            }
        }
    }
}

/**
 * Quanto durerà la panoramica, in una stima onesta.
 *
 * Ogni scatto costa lo spostamento fino alla posizione successiva, l'assestamento perché
 * l'immagine non venga mossa, e il tempo dell'otturatore. Lo spostamento si ricava dalla curva
 * misurata: senza profilo valido non si finge di saperlo e si risponde con un trattino. Il
 * tempo dello scatto è la stessa costante che usa la sequenza, così le due stime concordano.
 */
private fun panoramaDuration(
    plan: PanoramaPlan,
    profile: GimbalCalibrationProfile,
    settleSeconds: Float,
): String {
    if (!profile.isValid || plan.totalShots <= 1) return "—"
    val rate = maxOf(profile.maxAngularRate(panAxis = true), 1f)
    val stepDegrees = if (plan.columns > 1) plan.horizontalCenterSpan / (plan.columns - 1) else 0f
    val perShot = stepDegrees / rate + settleSeconds + TimelapseSequence.ESTIMATED_SHOT_SECONDS
    val seconds = (perShot * plan.totalShots).roundToInt()
    return if (seconds < 60) "${seconds}s" else "%d:%02d".format(seconds / 60, seconds % 60)
}
