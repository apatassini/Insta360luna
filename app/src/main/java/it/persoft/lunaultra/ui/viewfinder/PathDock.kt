package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.components.HudIconButton
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * Il percorso e la sua durata, sul mirino, quando ci sono dei punti.
 *
 * Un percorso ha due numeri: quanti punti tocca e in quanto tempo. Il primo si vede già sul
 * pulsante che li memorizza; il secondo stava solo dentro il pannello delle automazioni, e
 * cambiarlo voleva dire uscire dall'inquadratura, scorrere una schermata, digitare un numero e
 * tornare indietro. Ma la durata è la cosa che si aggiusta *mentre* si prova: si fa una
 * passata, viene troppo veloce, se ne fa un'altra più lenta. È un gesto da mirino.
 *
 * Compare solo quando i punti ci sono, perché prima non ha niente da dire. E dice anche cosa
 * sta per fare la modalità scelta — «registra percorrendoli», «scatta a ogni punto» — perché la
 * stessa fascia di comandi adesso si comporta in due modi a seconda che i punti ci siano o no,
 * e la differenza va vista prima di premere, non dopo.
 */
@Composable
fun PathDock(
    waypointCount: Int,
    totalSeconds: Float,
    perLegSeconds: Boolean,
    behaviourLabel: String?,
    accent: Color,
    onNudge: (Boolean) -> Unit,
    onOpenAutomations: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (waypointCount < 2 || behaviourLabel == null) return
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = modifier
            .background(Luna.Glass, shape)
            .border(1.dp, accent.copy(alpha = 0.45f), shape)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.clickable(onClick = onOpenAutomations),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    imageVector = LunaIcons.Sequence,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    text = "$waypointCount punti",
                    style = MaterialTheme.typography.labelMedium,
                    color = accent,
                )
            }
            Text(
                text = behaviourLabel,
                style = MaterialTheme.typography.labelSmall,
                color = Luna.OnSurfaceDim,
            )
        }
        HudIconButton(
            icon = LunaIcons.Down,
            contentDescription = "Percorso più lento",
            onClick = { onNudge(false) },
            size = 34.dp,
            enabled = totalSeconds > 1f,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatPathSeconds(totalSeconds),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Text(
                // Con più di due punti la durata si divide fra i tratti, e chi guarda deve
                // sapere se quel numero è tutto il percorso o solo il primo pezzo.
                text = if (perLegSeconds) "per tratto" else "in tutto",
                style = MaterialTheme.typography.labelSmall,
                color = Luna.OnSurfaceDim,
            )
        }
        HudIconButton(
            icon = LunaIcons.Up,
            contentDescription = "Percorso più lungo",
            onClick = { onNudge(true) },
            size = 34.dp,
        )
    }
}

/** Secondi in forma corta: sotto il minuto i secondi, sopra minuti e secondi. */
fun formatPathSeconds(seconds: Float): String {
    val total = seconds.roundToInt().coerceAtLeast(0)
    return if (total < 60) "${total}s" else "%d:%02d".format(total / 60, total % 60)
}
