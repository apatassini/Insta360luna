package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
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
    deleteOnFinish: Boolean,
    onDeleteOnFinish: (Boolean) -> Unit,
    onRun: (PanoJob) -> Unit,
    /** Allinea e fa scegliere il punto di vista, senza cucire. */
    onPrepare: (PanoJob) -> Unit,
    onRunAll: () -> Unit,
    /** Con che faccia si presenta un lavoro: il percorso di un'immagine, se c'è. */
    face: (PanoJob) -> String?,
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

        // I lavori scorrono: possono essere parecchi, e un elenco che deborda dallo schermo
        // nasconde proprio quelli piu` vecchi, che sono quelli che ci si dimentica di unire.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
        jobs.forEach { job ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // La faccia del lavoro, e la porta per entrarci. Un elenco di righe tutte
                // uguali — «9 scatti · 82° di campo» — non dice quale sia quale; la scena si`,
                // e a colpo d'occhio. E toccare l'immagine per aprirla e` il gesto che uno fa
                // da solo: l'iconcina accanto diceva la stessa cosa due volte.
                JobFace(
                    path = face(job),
                    chosen = job.viewChosen,
                    modifier = Modifier.clickable(enabled = !busy) { onPrepare(job) },
                )
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
                        text = if (job.viewChosen) {
                            jobDateLabel(job.createdAtMs) + " · punto di vista scelto"
                        } else {
                            jobDateLabel(job.createdAtMs)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (job.viewChosen) Luna.Ok else Luna.OnSurfaceDim,
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

        // Tutti insieme: e` il modo in cui questi lavori vogliono essere fatti davvero. Si
        // prepara in giro col telefono in mano, e la sera si mette in carica e si lancia il
        // mucchio — ognuno con il punto di vista che si e` scelto per lui.
        if (jobs.size > 1) {
            TextButton(
                onClick = onRunAll,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Uniscile tutte (${jobs.size})")
            }
        }

        // L'interruttore in fondo: a unione riuscita, buttare scatti e job oppure tenerli.
        // Spento, lo stesso job si rilancia quante volte si vuole: è il banco di prova
        // dell'unione, senza dover riscattare niente.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Cancella al termine",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
                Text(
                    text = if (deleteOnFinish) {
                        "A unione riuscita, scatti e job si buttano."
                    } else {
                        "Scatti e job restano: puoi rifare le prove quante volte vuoi."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Luna.OnSurfaceDim,
                )
            }
            Switch(checked = deleteOnFinish, onCheckedChange = onDeleteOnFinish)
        }
    }
}

private fun jobDateLabel(timeMs: Long): String =
    SimpleDateFormat("EEEE d MMMM · HH:mm", Locale.getDefault())
        .format(Date(timeMs))
        .replaceFirstChar { it.uppercase(Locale.getDefault()) }

/**
 * La miniatura di un lavoro, decodificata piccola e ricordata finche` la riga vive.
 *
 * Piccola davvero: `inSampleSize` fa saltare il decodificatore a passi di due, quindi da uno
 * scatto da trentasette megapixel si arriva a un francobollo senza mai tenere in memoria
 * l'originale. Un elenco di miniature deve costare quanto un elenco.
 */
@Composable
private fun JobFace(path: String?, chosen: Boolean, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, path) {
        value = if (path == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    val longest = maxOf(bounds.outWidth, bounds.outHeight)
                    var sample = 1
                    while (longest / sample > FACE_PIXELS * 2) sample *= 2
                    BitmapFactory.decodeFile(
                        path,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    )
                }.getOrNull()
            }
        }
    }
    Box(
        modifier = modifier
            .size(FACE_SIZE)
            .clip(RoundedCornerShape(8.dp))
            .background(Luna.Surface)
            // Il bordo verde dice che il punto di vista e` gia` scelto: la stessa cosa che
            // diceva l'iconcina accesa, detta sull'immagine a cui si riferisce.
            .border(
                width = if (chosen) 2.dp else 0.dp,
                color = if (chosen) Luna.Ok else Color.Transparent,
                shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        bitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private val FACE_SIZE = 68.dp
private const val FACE_PIXELS = 200
