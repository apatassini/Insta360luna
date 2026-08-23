package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.timelapse.LunaOptics
import it.persoft.lunaultra.timelapse.PanoramaPreset
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.NumberField
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

        SectionCard(title = "Quanto coprire", icon = LunaIcons.Panorama, accent = Luna.Pano) {
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
                LabeledValue("Griglia", "${current.columns} colonne × ${current.rows} righe")
                LabeledValue("Scatti", current.totalShots.toString())
                LabeledValue(
                    "Copertura reale",
                    "%.0f° × %.0f°".format(
                        current.horizontalCenterSpan + current.fieldOfView.horizontalDegrees,
                        current.verticalCenterSpan + current.fieldOfView.verticalDegrees,
                    ),
                )
                current.warning?.let { Hint(it) }
                Button(
                    onClick = viewModel::shootPanorama,
                    enabled = connected && !run.running,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(LunaIcons.Panorama, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("  Scatta la panoramica (${current.totalShots} foto)")
                }
                Hint(
                    "Parte dall'inquadratura attuale, che diventa il centro della panoramica. " +
                        "La camera percorre la griglia a serpentina e scatta a ogni posizione, " +
                        "fermandosi il tempo necessario perché l'immagine non venga mossa.",
                )
            }
        }
    }
}
