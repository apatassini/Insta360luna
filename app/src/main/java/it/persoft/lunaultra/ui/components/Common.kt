package it.persoft.lunaultra.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.theme.Luna

/**
 * Riquadro di una sezione nei pannelli a schermo intero.
 *
 * Le schede tecniche (sequenza, impostazioni, diagnostica) sono tutte fatte di queste: un titolo
 * con la sua icona e un contenuto. Averne una sola forma è ciò che tiene insieme visivamente
 * pannelli scritti in momenti diversi.
 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color = Luna.Accent,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .border(1.dp, Luna.GlassBorder, RoundedCornerShape(20.dp)),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Luna.Surface),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (icon != null) {
                        // Ogni sezione ha il suo colore: scorrendo il pannello si riconosce
                        // dove si è senza rileggere i titoli.
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .background(accent.copy(alpha = 0.16f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = accent,
                                modifier = Modifier.size(17.dp),
                            )
                        }
                    }
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                }
                trailing?.invoke()
            }
            content()
        }
    }
}

@Composable
fun LabeledValue(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Luna.Accent,
    icon: ImageVector? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = Luna.OnSurfaceDim, modifier = Modifier.size(16.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Luna.OnSurfaceDim,
            )
        }
        Text(text = value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

/** Campo numerico che notifica solo i valori validi, lasciando digitare liberamente. */
@Composable
fun NumberField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Number,
    supportingText: String? = null,
    leadingIcon: ImageVector? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(14.dp),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        supportingText = supportingText?.let { { Text(it) } },
        leadingIcon = leadingIcon?.let { { Icon(it, contentDescription = null) } },
        modifier = modifier,
    )
}

/** Interruttore con la sua spiegazione: la riga intera è il bersaglio, non solo il pallino. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = Luna.OnSurfaceDim, modifier = Modifier.size(20.dp))
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Luna.OnSurfaceDim,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** Cursore con il valore scritto sopra: senza il numero un cursore non è un'impostazione. */
@Composable
fun SliderRow(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    valueLabel: String = value.toString(),
    steps: Int = 0,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = Luna.OnSurfaceDim, modifier = Modifier.size(16.dp))
                }
                Text(text = label, style = MaterialTheme.typography.bodyMedium, color = Luna.OnSurfaceDim)
            }
            Text(text = valueLabel, style = MaterialTheme.typography.labelLarge, color = Luna.Accent)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
        )
    }
}

/** Nota esplicativa sotto un comando. Grigia e piccola: si legge se serve, non disturba. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = Luna.OnSurfaceDim,
        modifier = modifier,
    )
}

/**
 * Un numero grande con la sua didascalia sotto.
 *
 * Una scheda tecnica fatta di dieci righe etichetta-valore tutte uguali si legge una riga per
 * volta, e nessuna di quelle righe si vede con la coda dell'occhio. Le tessere invece hanno una
 * gerarchia: il numero è grande e si prende lo sguardo, la didascalia spiega cosa sia e sta
 * sotto, in piccolo. Servono per i pochi valori che contano davvero in una schermata — non per
 * tutti, o tornano a essere un elenco.
 */
@Composable
fun MetricTile(
    value: String,
    caption: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Luna.OnSurface,
    accent: Color? = null,
) {
    Column(
        modifier = modifier
            .background(Luna.SurfaceHigh, RoundedCornerShape(14.dp))
            .then(
                if (accent != null) Modifier.border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
                else Modifier,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            color = valueColor,
            maxLines = 1,
        )
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            color = Luna.OnSurfaceDim,
            maxLines = 2,
        )
    }
}

/** Due o tre tessere affiancate, tutte della stessa larghezza. */
@Composable
fun MetricRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/**
 * Pastiglia di stato con il pallino colorato.
 *
 * Il pallino fa il lavoro: verde, ambra o rosso si riconoscono senza leggere, e la scritta
 * accanto serve solo a chi vuole sapere di preciso. [pulsing] la fa respirare, e va usato solo
 * quando qualcosa sta davvero succedendo in questo momento — altrimenti è rumore che si muove.
 */
@Composable
fun StatusChip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    pulsing: Boolean = false,
) {
    val alpha = if (pulsing) {
        val transition = rememberInfiniteTransition(label = "chip")
        transition.animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "chipAlpha",
        ).value
    } else {
        1f
    }
    Row(
        modifier = modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(color.copy(alpha = alpha), CircleShape),
        )
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/**
 * La curva delle intensità come barre, invece che come dodici righe di numeri.
 *
 * La forma della curva è l'informazione: se sale regolare va bene, se ha un gradino o un
 * avvallamento c'è qualcosa da guardare — ed è esattamente il caso del comando 100, che su
 * questa camera muove un sesto del 90. In un elenco di numeri quel gradino va cercato; in un
 * grafico salta all'occhio senza leggere niente, e infatti la barra che scende si colora
 * diversamente perché non ci sia dubbio su quale sia.
 *
 * L'area delle barre e la riga delle etichette sono due zone distinte, e la barra si misura
 * dentro la sua. Facendo altrimenti — barra in frazione dell'altezza totale, etichetta sotto —
 * le barre alte sforano di quanto è alta l'etichetta, e finiscono per scavalcarla.
 */
@Composable
fun CurveBars(
    values: List<Pair<Int, Float>>,
    modifier: Modifier = Modifier,
    color: Color = Luna.Accent,
    height: Dp = 72.dp,
) {
    if (values.isEmpty()) return
    val ordered = values.sortedBy { it.first }
    val maximum = ordered.maxOf { it.second }.takeIf { it > 0f } ?: return
    // Con dodici barre le etichette si toccherebbero: si scrivono la prima, l'ultima e una sì
    // e una no. Gli estremi sono quelli che si vogliono leggere sempre.
    val labelEvery = if (ordered.size > 8) 2 else 1
    Row(
        modifier = modifier.fillMaxWidth().height(height),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        ordered.forEachIndexed { index, (intensity, value) ->
            val previous = ordered.getOrNull(index - 1)?.second
            val slower = previous != null && value < previous
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight((value / maximum).coerceIn(0.03f, 1f))
                            .background(
                                if (slower) Luna.Warn.copy(alpha = 0.85f) else color.copy(alpha = 0.75f),
                                RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp),
                            ),
                    )
                }
                val showLabel = index == 0 || index == ordered.lastIndex || index % labelEvery == 0
                Text(
                    text = if (showLabel) "$intensity" else "",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (slower) Luna.Warn else Luna.OnSurfaceDim,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Contenuto di un pulsante con icona: l'icona, lo spazio giusto, e la scritta.
 *
 * Prima lo spazio era due caratteri dentro il testo — `Text("  Aggiorna")` — e su un pulsante
 * stretto quei due spazi non bastano: l'icona finisce addosso alla parola. Lo spazio fra icona
 * e testo di un pulsante ha una misura sua in Material, ed è quella che va usata. La scritta si
 * accorcia con i puntini invece di andare sotto l'icona quando il pulsante è più stretto di lei.
 */
@Composable
fun RowScope.ButtonLabel(icon: ImageVector, label: String) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
    Text(text = label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}
