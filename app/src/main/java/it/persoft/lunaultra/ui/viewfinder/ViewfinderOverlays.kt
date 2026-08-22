package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.camera.ConnectionState
import it.persoft.lunaultra.timelapse.RunState
import it.persoft.lunaultra.ui.components.GlassPanel
import it.persoft.lunaultra.ui.italianLabel
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * Avanzamento della sequenza in corso, sopra l'anteprima.
 *
 * Resta visibile anche quando i comandi sono nascosti: mentre la sequenza gira, l'unica cosa
 * che serve sempre a portata di dito è fermarla.
 */
@Composable
fun RunCard(
    run: RunState,
    mode: CaptureMode,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier = modifier.width(300.dp), contentPadding = 12.dp, verticalSpacing = 8.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(mode.icon, contentDescription = null, tint = Luna.Accent, modifier = Modifier.size(18.dp))
            Text(
                text = run.phase.italianLabel(),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(run.overallProgress * 100).roundToInt()}%",
                style = MaterialTheme.typography.labelLarge,
                color = Luna.Accent,
            )
        }

        LinearProgressIndicator(
            progress = { run.overallProgress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )

        Text(
            text = buildString {
                append("Tratto ${run.legIndex + 1}/${run.legCount.coerceAtLeast(1)}")
                if (run.shotsPlanned > 0) append("  ·  scatto ${run.shotsTaken}/${run.shotsPlanned}")
                append("  ·  target %.1f° / %.1f°".format(run.targetPan, run.targetTilt))
            },
            style = MaterialTheme.typography.labelSmall,
            color = Luna.OnSurfaceDim,
        )

        run.message?.let {
            Text(text = it, style = MaterialTheme.typography.labelSmall, color = Luna.OnSurfaceDim)
        }

        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = Luna.Rec,
                contentColor = Color.White,
            ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(LunaIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text = "  STOP")
        }
    }
}

/**
 * Invito a connettersi, al centro dell'anteprima nera.
 *
 * È una pastiglia e non un riquadro: senza camera non c'è niente da inquadrare, ma coprire
 * mezzo schermo — e i comandi del gimbal che ci stanno sotto — per dire una cosa sola è troppo.
 * L'indirizzo sta sotto in piccolo, perché serve solo quando qualcosa non va.
 */
@Composable
fun ConnectCta(
    connection: ConnectionState,
    host: String,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val busy = connection == ConnectionState.CONNECTING || connection == ConnectionState.HANDSHAKE
    val failed = connection == ConnectionState.ERROR
    val shape = RoundedCornerShape(26.dp)

    Row(
        modifier = modifier
            .background(Luna.Glass, shape)
            .border(1.dp, if (failed) Luna.Warn.copy(alpha = 0.6f) else Luna.GlassBorder, shape)
            .clickable(enabled = !busy, onClick = onConnect)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Luna.Accent,
            )
        } else {
            Icon(
                imageVector = if (failed) LunaIcons.Warning else LunaIcons.Connected,
                contentDescription = null,
                tint = if (failed) Luna.Warn else Luna.Accent,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = when {
                    busy -> "Connessione…"
                    failed -> "Riprova a connetterti"
                    else -> "Connetti alla camera"
                },
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            Text(
                text = host,
                style = MaterialTheme.typography.labelSmall,
                color = Luna.OnSurfaceDim,
            )
        }
    }
}

/**
 * Perché l'anteprima è nera.
 *
 * Quando la camera è connessa ma non arriva un fotogramma, la differenza fra «spenta»,
 * «in avvio» e «rifiutata dalla camera» è tutta l'informazione che c'è, e va scritta.
 */
@Composable
fun PreviewStatusNote(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Luna.GlassSoft, MaterialTheme.shapes.large)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * L'elenco completo delle modalità, aperto dalla ghiera.
 *
 * La ghiera in fondo mostra i nomi e basta, che è quello che serve mentre si riprende; quando
 * invece si sta ancora decidendo, qui c'è anche cosa fa ognuna e se è utilizzabile — una
 * modalità guidata senza punti memorizzati non parte, e conviene saperlo prima di sceglierla.
 */
@Composable
fun ModeSheet(
    selected: CaptureMode,
    sequenceReady: Boolean,
    onSelect: (CaptureMode) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    GlassPanel(modifier = modifier.width(330.dp), contentPadding = 12.dp, verticalSpacing = 6.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Modalità",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
            Icon(
                imageVector = LunaIcons.Close,
                contentDescription = "Chiudi",
                tint = Luna.OnSurfaceDim,
                modifier = Modifier.size(20.dp).clickable(onClick = onDismiss),
            )
        }
        CaptureMode.entries.forEach { mode ->
            val usable = !mode.usesSequence || sequenceReady
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = if (mode == selected) mode.color.copy(alpha = 0.14f) else Color.Transparent,
                        shape = RoundedCornerShape(14.dp),
                    )
                    .clickable {
                        onSelect(mode)
                        onDismiss()
                    }
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .background(mode.color.copy(alpha = 0.18f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = mode.icon,
                        contentDescription = null,
                        tint = mode.color,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (mode == selected) mode.color else Color.White,
                    )
                    Text(
                        text = if (usable) mode.hint else "Servono almeno due punti memorizzati",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (usable) Luna.OnSurfaceDim else Luna.Warn,
                    )
                }
                if (mode == selected) {
                    Icon(
                        imageVector = LunaIcons.Check,
                        contentDescription = null,
                        tint = mode.color,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}
