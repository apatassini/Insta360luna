package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.stitch.PanoJob
import it.persoft.lunaultra.ui.components.GlassPanel
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * La scheda dei lavori: le panoramiche scattate e non ancora unite.
 *
 * Gli scatti sono già al sicuro sul telefono; qui si decide *quando* pagare i minuti
 * dell'unione. Il triangolo la lancia, la X annulla il job — e annullare butta via solo la
 * voce dall'elenco: le foto restano in `DCIM › Luna Ultra › Panoramiche`, dove si possono
 * sempre riunire a mano scegliendole dalla galleria.
 */
@Composable
fun PanoJobsSheet(
    jobs: List<PanoJob>,
    busy: Boolean,
    onRun: (PanoJob) -> Unit,
    onCancel: (PanoJob) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(
        modifier = modifier.fillMaxWidth(),
        background = Luna.Surface,
        contentPadding = 12.dp,
        verticalSpacing = 10.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = LunaIcons.Jobs,
                contentDescription = null,
                tint = Luna.Pano,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "PANORAMICHE DA UNIRE",
                style = MaterialTheme.typography.labelLarge,
                color = Luna.Pano,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = LunaIcons.Close,
                contentDescription = "Chiudi",
                tint = Luna.OnSurfaceDim,
                modifier = Modifier.size(20.dp).clickable(onClick = onClose),
            )
        }

        if (jobs.isEmpty()) {
            Text(
                text = "Nessun lavoro in attesa. Quando scatti una panoramica, gli scatti " +
                    "si scaricano qui e li unisci quando vuoi — anche stasera.",
                style = MaterialTheme.typography.bodySmall,
                color = Luna.OnSurfaceDim,
            )
        }

        jobs.forEach { job ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = buildString {
                            append("${job.files.size} scatti · ")
                            append(if (job.spherical) "sferica" else "${job.fovDegrees.roundToInt()}° di campo")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                    )
                    Text(
                        text = jobDateLabel(job.createdAtMs),
                        style = MaterialTheme.typography.labelSmall,
                        color = Luna.OnSurfaceDim,
                    )
                }
                HudIconButton(
                    icon = LunaIcons.Play,
                    contentDescription = "Unisci adesso",
                    onClick = { onRun(job) },
                    enabled = !busy,
                    size = 40.dp,
                    selected = !busy,
                    activeColor = Luna.Pano,
                )
                HudIconButton(
                    icon = LunaIcons.Close,
                    contentDescription = "Annulla il job (le foto restano)",
                    onClick = { onCancel(job) },
                    size = 40.dp,
                    activeColor = Luna.Rec,
                )
            }
        }
    }
}

private fun jobDateLabel(timeMs: Long): String =
    SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.getDefault())
        .format(Date(timeMs))
        .replaceFirstChar { it.uppercase(Locale.getDefault()) }
