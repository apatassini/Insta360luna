package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * Quanti punti e in quanto tempo, in una pastiglia sola sopra i comandi.
 *
 * La durata è la cosa che si aggiusta *mentre* si prova: si fa una passata, viene troppo
 * veloce, se ne fa un'altra più lenta. Per questo sta qui e non solo dentro le automazioni.
 *
 * Ma sta nello spazio di una pastiglia, non di una barra. Il primo tentativo occupava tutta la
 * larghezza dello schermo con due righe di testo, e finiva sopra la fila degli zoom: un comando
 * che si usa ogni tanto si era preso il posto di uno che si usa in continuazione, e per giunta
 * lo copriva. Adesso è nella riga che l'app tiene già per l'informazione della modalità —
 * quella dove in panoramica compare «sferica 360°» — e ne prende solo il posto.
 *
 * Quello che è rimasto fuori non manca: il comportamento della modalità («registra
 * percorrendoli») era una riga di spiegazione, e una spiegazione si legge una volta. I punti si
 * contano sul pulsante che li memorizza. Qui restano i due numeri che cambiano: quanti punti e
 * quanti secondi. Toccando il centro si aprono le automazioni, per tutto il resto.
 */
@Composable
fun PathDock(
    waypointCount: Int,
    totalSeconds: Float,
    accent: Color,
    onNudge: (Boolean) -> Unit,
    onOpenAutomations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (waypointCount < 2) return
    Row(
        modifier = modifier
            .height(DOCK_HEIGHT)
            .background(Luna.Glass, CircleShape)
            .border(1.dp, accent.copy(alpha = 0.4f), CircleShape)
            .padding(horizontal = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // La freccia in giù toglie secondi, quella in su ne aggiunge: la direzione segue il
        // numero, non la velocità del gimbal — che va nel verso opposto e confonderebbe.
        NudgeButton(LunaIcons.Down, "Percorso più corto", enabled = totalSeconds > 1f) { onNudge(false) }
        Row(
            modifier = Modifier
                .clickable(onClick = onOpenAutomations)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = LunaIcons.Sequence,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(13.dp),
            )
            Text(
                text = "$waypointCount",
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
            Text(
                text = formatPathSeconds(totalSeconds),
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
            )
        }
        NudgeButton(LunaIcons.Up, "Percorso più lungo") { onNudge(true) }
    }
}

@Composable
private fun NudgeButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = if (enabled) Luna.OnSurface else Luna.OnSurfaceDim.copy(alpha = 0.4f),
        modifier = Modifier
            .size(NUDGE_SIZE)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(5.dp),
    )
}

/** Secondi in forma corta: sotto il minuto i secondi, sopra minuti e secondi. */
fun formatPathSeconds(seconds: Float): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    return if (total < 60) "${total}s" else "%d:%02d".format(total / 60, total % 60)
}

/** L'altezza della pastiglia dell'informazione, la stessa delle altre di questa riga. */
private val DOCK_HEIGHT = 34.dp

/** Bersaglio dei due ritocchi: piccolo ma non meno di quanto un pollice trova. */
private val NUDGE_SIZE = 30.dp
