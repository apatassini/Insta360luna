package it.persoft.lunaultra.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.theme.Luna
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt

/** Le tacche del misuratore: otto, come i core del telefono su cui è nato. */
private const val SEGMENTS = 8

/** Ogni quanto scende la punta, e di quanto. Mezzo secondo per tornare giù di una tacca. */
private const val PEAK_STEP_MS = 60L
private const val PEAK_FALL = 1f / SEGMENTS / 8f

/**
 * Il misuratore a tacche di un banco da mixaggio, per il lavoro invece che per il suono.
 *
 * Un numero che cambia dieci volte al secondo non si legge: l'occhio lo vede lampeggiare e non
 * ne ricava niente. Una fila di tacche sì — la si guarda di sfuggita e si sa subito se la
 * macchina sta macinando o dorme, che è l'unica domanda che ci si fa mentre si aspettano
 * minuti. Il numero resta di fianco, per chi lo vuole preciso.
 *
 * La **punta** è il pezzo che lo rende un misuratore vero e non una barra. Il valore istantaneo
 * balla; la punta tiene il massimo recente e scende piano, e dice una cosa che l'istante non
 * dice: se quella fase ha mai lavorato in parallelo. Otto core mediamente occupati due significa
 * o «due core sempre» o «otto core un quarto del tempo», e sono due mondi diversi — la punta
 * ferma su due dice il primo, la punta alta dice il secondo.
 *
 * @param filled da zero a uno; `null` quando non è misurabile, che è diverso da zero.
 */
@Composable
fun LoadMeter(
    label: String,
    filled: Float?,
    reading: String,
    modifier: Modifier = Modifier,
    lit: Color = Luna.Ok,
) {
    val live = rememberUpdatedState(filled ?: 0f)
    var peak by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        while (true) {
            peak = max(live.value, peak - PEAK_FALL)
            delay(PEAK_STEP_MS)
        }
    }

    val level = (filled ?: 0f).coerceIn(0f, 1f)
    // Per eccesso: mezza tacca accesa vuol dire che qualcosa sta succedendo, e spegnerla
    // direbbe il contrario.
    val on = if (filled == null) 0 else (level * SEGMENTS).roundToInt().coerceIn(0, SEGMENTS)
    val peakAt = if (filled == null) -1 else ((peak * SEGMENTS).roundToInt() - 1).coerceIn(-1, SEGMENTS - 1)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Luna.OnSurfaceDim,
            modifier = Modifier.width(30.dp),
        )
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(SEGMENTS) { index ->
                val colour = when {
                    index < on -> lit
                    index == peakAt -> lit.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = 0.10f)
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(9.dp)
                        .background(colour, RoundedCornerShape(1.dp)),
                )
            }
        }
        Text(
            text = reading,
            style = MaterialTheme.typography.labelSmall,
            color = if (filled == null) Luna.OnSurfaceDim else Color.White,
            textAlign = TextAlign.End,
            modifier = Modifier.width(30.dp),
        )
    }
}

/** Il numero di fianco al misuratore, nella forma che si legge a colpo d'occhio: `3/8`. */
fun eighths(value: Float?, outOf: Int = SEGMENTS): String {
    if (value == null) return "–/$outOf"
    return "${(value.coerceIn(0f, 1f) * outOf).roundToInt()}/$outOf"
}
