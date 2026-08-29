package it.persoft.lunaultra.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.gimbal.FindingKind
import it.persoft.lunaultra.gimbal.GimbalCalibrationState
import it.persoft.lunaultra.ui.components.CurveBars
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.MetricRow
import it.persoft.lunaultra.ui.components.MetricTile
import it.persoft.lunaultra.ui.components.StatusChip
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * La calibrazione mentre succede: cosa sta rilevando, e cosa ha già accertato.
 *
 * Sette minuti di barra di avanzamento chiedono fiducia senza dare niente in cambio, e quando
 * finiscono male non si sa perché. Qui si vedono tre cose, in quest'ordine: la corsa in atto —
 * quanti impulsi si sono dati e se il fine corsa si è fatto sentire — la curva che si sta
 * costruendo, e l'elenco dei fatti accertati finora, il più recente in cima.
 *
 * Le percentuali di somiglianza fra immagini non ci sono più: erano il metodo di prima. La curva
 * adesso si conta a impulsi contro il segnale hardware del limite, e il pannello mostra quello.
 * I fotogrammi restano dove servono ancora davvero — la ricerca dei fine corsa e i collaudi di
 * andata e ritorno — e solo lì.
 */
@Composable
fun CalibrationLiveReport(
    state: GimbalCalibrationState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(state.phaseLabel, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${(state.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.titleMedium,
                    color = Luna.Accent,
                )
            }
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(state.message, style = MaterialTheme.typography.bodySmall, color = Luna.OnSurfaceDim)
        }

        EndstopHunt(state)

        if (state.curve.isNotEmpty()) {
            LiveCurve(state)
        }

        if (state.findings.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "RILEVATO FINORA",
                    style = MaterialTheme.typography.labelSmall,
                    color = Luna.OnSurfaceDim,
                )
                state.findings.take(6).forEach { finding ->
                    FindingRow(finding.label, finding.detail, finding.kind)
                }
                if (state.findings.size > 6) {
                    Hint("Gli altri ${state.findings.size - 6} rilevamenti sono nel log della Diagnostica.")
                }
            }
        }

        // I fotogrammi contano solo dove servono davvero: la ricerca dei limiti li usa come
        // conferma del segnale hardware, e i collaudi di andata e ritorno li usano come metro.
        // Nella misura della curva non c'entrano niente, e mostrarli lì sarebbe una bugia.
        if (state.usesImages) {
            ImageEvidence(state)
        }

        if (state.pausedForPreview) {
            Hint(
                "Calibrazione in pausa, non interrotta. Il servizio mantiene camera, Wi-Fi e " +
                    "misure; riaprendo l'app riprende dal passaggio corrente.",
            )
        }

        OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
            Text("Interrompi calibrazione")
        }
    }
}

/**
 * La corsa verso il fine corsa, che è il cuore della misura nuova.
 *
 * Il numero grande sono gli impulsi dati: è quello che si conta, quindi è quello che si guarda.
 * La pastiglia dice se il limite si è già fatto sentire, e la barra quanto manca al tetto di
 * sicurezza — se si riempie senza che il segnale arrivi, quella misura è persa e si vede
 * arrivare invece di scoprirlo dal messaggio d'errore.
 */
@Composable
private fun EndstopHunt(state: GimbalCalibrationState) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CORSA IN ATTO", style = MaterialTheme.typography.labelSmall, color = Luna.OnSurfaceDim)
            when {
                state.endstopReached -> StatusChip("Fine corsa agganciato", Luna.Ok)
                state.seekingEndstop -> StatusChip("Aspetto il segnale 8302", Luna.Amber, pulsing = true)
                else -> StatusChip("Ferma", Luna.OnSurfaceDim)
            }
        }
        MetricRow {
            MetricTile(
                value = if (state.maxPulsesInRun > 0) "${state.pulsesInRun}" else "—",
                caption = if (state.maxPulsesInRun > 0) {
                    "impulsi dati · tetto ${state.maxPulsesInRun}"
                } else {
                    "impulsi dati"
                },
                valueColor = Luna.Accent,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                value = if (state.intensityPercent > 0) "${state.intensityPercent}%" else "—",
                caption = "${state.axisLabel} · ${state.directionLabel}",
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                value = "${state.endstopSignals}",
                caption = "limiti annunciati dalla camera",
                valueColor = if (state.endstopSignals > 0) Luna.Ok else Luna.OnSurface,
                modifier = Modifier.weight(1f),
            )
        }
        if (state.maxPulsesInRun > 0) {
            LinearProgressIndicator(
                progress = { state.runProgress },
                color = if (state.runProgress > 0.85f) Luna.Warn else Luna.AccentDim,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** La curva che si sta costruendo, un asse per volta e in forma di barre. */
@Composable
private fun LiveCurve(state: GimbalCalibrationState) {
    val pan = state.curve.filter { it.panAxis }
    val tilt = state.curve.filterNot { it.panAxis }
    val latest = state.curve.last()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("CURVA MISURATA", style = MaterialTheme.typography.labelSmall, color = Luna.OnSurfaceDim)
            Text(
                "ultima misura · %.2f °/s".format(latest.degreesPerSecond),
                style = MaterialTheme.typography.labelSmall,
                color = Luna.Accent,
            )
        }
        if (pan.isNotEmpty()) {
            Text("Orizzontale · ${pan.size}/12", style = MaterialTheme.typography.bodySmall, color = Luna.OnSurfaceDim)
            CurveBars(pan.map { it.intensityPercent to it.degreesPerSecond }, color = Luna.Accent)
        }
        if (tilt.isNotEmpty()) {
            Text("Verticale · ${tilt.size}/12", style = MaterialTheme.typography.bodySmall, color = Luna.OnSurfaceDim)
            CurveBars(tilt.map { it.intensityPercent to it.degreesPerSecond }, color = Luna.Pano)
        }
        Hint("Altezza = gradi al secondo letti dal giroscopio · sotto, l'intensità comandata.")
    }
}

/** Il confronto fra fotogrammi, mostrato solo nelle fasi che lo usano davvero. */
@Composable
private fun ImageEvidence(state: GimbalCalibrationState) {
    val bitmap = remember(state.annotatedJpeg) {
        state.annotatedJpeg?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CONFERMA VISIVA", style = MaterialTheme.typography.labelSmall, color = Luna.OnSurfaceDim)
        Text(state.verificationLabel, style = MaterialTheme.typography.bodySmall)
        LabeledValue(
            "Spostamento immagine",
            "Δx %+.1f px · Δy %+.1f px".format(state.shiftX, state.shiftY),
        )
        LabeledValue(
            "Punti coerenti",
            "${state.inlierMatches}/${state.candidateMatches} · ${state.controlPointsPercent}%",
            valueColor = when {
                state.controlPointsPercent >= 70 -> Luna.Ok
                state.controlPointsPercent >= 45 -> Luna.Warn
                else -> Luna.Rec
            },
        )
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Punti di controllo della calibrazione",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .border(1.dp, Luna.GlassBorder, RoundedCornerShape(14.dp)),
            )
            Hint("Verde = punti coerenti con il movimento · rosso = corrispondenze scartate.")
        }
    }
}

@Composable
private fun FindingRow(label: String, detail: String, kind: FindingKind) {
    val color = when (kind) {
        FindingKind.GOOD -> Luna.Ok
        FindingKind.WARN -> Luna.Warn
        FindingKind.FACT -> Luna.Accent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Luna.SurfaceHigh, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = when (kind) {
                FindingKind.WARN -> LunaIcons.Warning
                else -> LunaIcons.Check
            },
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = color)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = Luna.OnSurfaceDim)
        }
    }
}

/**
 * Il profilo salvato, riassunto in quello che serve davvero sapere.
 *
 * Prima erano dodici righe di pixel al secondo, che nei profili nuovi valgono zero perché la
 * curva non nasce più dalle immagini. Adesso: quanto si muove al massimo, con che comando, e le
 * corse misurate. Il comando più veloce ha una riga tutta sua perché su questa camera non è il
 * 100% — ed è il tipo di cosa che va vista, non dedotta.
 */
@Composable
fun CalibrationProfileSummary(profile: GimbalCalibrationProfile, modifier: Modifier = Modifier) {
    val panFastest = profile.fastestCommandPercent(panAxis = true)
    val tiltFastest = profile.fastestCommandPercent(panAxis = false)
    val quirks = profile.nonMonotonicPoints(panAxis = true) + profile.nonMonotonicPoints(panAxis = false)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        MetricRow {
            MetricTile(
                value = "%.0f°/s".format(profile.maxAngularRate(panAxis = true)),
                caption = "massimo orizzontale · comando $panFastest%",
                valueColor = Luna.Accent,
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                value = "%.0f°/s".format(profile.maxAngularRate(panAxis = false)),
                caption = "massimo verticale · comando $tiltFastest%",
                valueColor = Luna.Pano,
                modifier = Modifier.weight(1f),
            )
        }
        val panCurve = profile.responsePoints
            .map { it.intensityPercent to profile.responseRateAt(it.intensityPercent.toFloat(), true) }
            .filter { it.second > 0f }
        if (panCurve.isNotEmpty()) {
            CurveBars(panCurve, color = Luna.Accent)
            Hint("Curva orizzontale: altezza = gradi al secondo, sotto l'intensità comandata.")
        }
        LabeledValue(
            "Corsa orizzontale",
            "%.0f°…%+.0f°".format(profile.panLimits.minimumDeg, profile.panLimits.maximumDeg),
            valueColor = Luna.Ok,
        )
        LabeledValue(
            "Corsa verticale",
            "%.0f°…%+.0f°".format(profile.tiltLimits.minimumDeg, profile.tiltLimits.maximumDeg),
            valueColor = Luna.Ok,
        )
        LabeledValue("Assestamento", "${profile.settleMs} ms")
        if (quirks.isNotEmpty()) {
            val worst = quirks.maxByOrNull { abs(it.second) }
            if (worst != null) {
                FindingRow(
                    "Il ${worst.first}% muove meno di un comando più basso",
                    "%.0f °/s in meno. Non è un errore di misura: la camera fa così, e l'app " +
                        "evita quel comando quando le si chiede il massimo."
                        .format(abs(worst.second)),
                    FindingKind.WARN,
                )
            }
        }
    }
}

/** Il riquadro rosso di un tentativo non riuscito, in cima dove si guarda. */
@Composable
fun CalibrationFailureNotice(reason: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Luna.Rec.copy(alpha = 0.08f), RoundedCornerShape(12.dp))
            .border(1.dp, Luna.Rec, RoundedCornerShape(12.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(LunaIcons.Warning, contentDescription = null, tint = Luna.Rec, modifier = Modifier.size(16.dp))
            Text(
                "CALIBRAZIONE NON SALVATA",
                style = MaterialTheme.typography.labelMedium,
                color = Luna.Rec,
            )
        }
        Text(reason, style = MaterialTheme.typography.bodySmall)
        Text(
            "Il profilo precedente è rimasto com'era. La Diagnostica ha il log completo di " +
                "questo tentativo, miniature comprese.",
            style = MaterialTheme.typography.bodySmall,
            color = Luna.OnSurfaceDim,
        )
    }
}

/** Il pulsante che avvia o rifà la calibrazione, con quello che c'è da sapere prima. */
@Composable
fun CalibrationStartBlock(
    hasProfile: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = Luna.Pano,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = onStart, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(LunaIcons.Center, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (hasProfile) "  Rifai la calibrazione" else "  Avvia la calibrazione")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusChip("5–8 minuti", accent)
            StatusChip("camera libera di girare", Luna.OnSurfaceDim)
        }
        Hint(
            "Prima cerca i quattro fine corsa, poi conta gli impulsi da un limite all'altro per " +
                "ogni intensità da 1% a 100%, sui due assi. Il profilo precedente resta valido " +
                "se la prova viene interrotta o non è affidabile.",
        )
    }
}
