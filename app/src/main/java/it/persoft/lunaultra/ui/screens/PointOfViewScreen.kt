package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.stitch.StitchProjection
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.ButtonLabel
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * La fase intermedia: da dove guardare la panoramica.
 *
 * Una panoramica non ha un diritto e un rovescio. I fotogrammi stanno su una sfera, e stenderla
 * su un rettangolo deforma per forza: quanto e **dove** dipende da quale punto della sfera
 * finisce al centro. Lo stesso ramo che nell'equirettangolare centrata sull'orizzonte si allarga
 * cinque volte, portato al centro non si allarga affatto — e a pagare diventa qualcos'altro.
 *
 * Non è una scelta che si fa leggendo dei numeri: si fa guardando, e col dito sopra. Per questo
 * la foto si prende tutto lo spazio che avanza e i comandi che la modificano stanno **attaccati
 * sotto**, dove il pollice arriva senza scorrere: chi deve confrontare un cambiamento con
 * l'immagine non può avere il comando a mezza pagina di distanza. I numeri, che si leggono e
 * non si toccano, stanno più in basso.
 */
@Composable
fun PointOfViewScreen(viewModel: MainViewModel) {
    val image by viewModel.pointOfViewImage.collectAsState()
    val view by viewModel.pointOfView.collectAsState()
    val shape by viewModel.pointOfViewShape.collectAsState()
    val dragging by viewModel.pointOfViewDragging.collectAsState()
    // Dentro un job la scelta si può anche solo salvare: la cucitura si lancia quando si vuole.
    val forJob by viewModel.pointOfViewForJob.collectAsState()

    // Dove sta il dito adesso, in pixel dello schermo: serve solo a disegnarci sopra il segno.
    var finger by remember { mutableStateOf<Offset?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // ---- La foto, con tutto lo spazio che avanza ----
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            val painted = image
            if (painted == null) {
                CircularProgressIndicator()
            } else {
                Image(
                    bitmap = painted.bitmap.asImageBitmap(),
                    contentDescription = "Anteprima della panoramica",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(painted) {
                            // Il rapporto fra pixel dello schermo e gradi di panoramica, preso
                            // sull'immagine come viene mostrata davvero: l'anteprima dichiara
                            // quanti gradi copre, e «Fit» la rimpicciolisce fino a starci. Senza
                            // questo conto il dito e l'immagine si staccano appena cambia la
                            // proiezione o il ritaglio.
                            val fit = min(
                                size.width.toFloat() / painted.bitmap.width,
                                size.height.toFloat() / painted.bitmap.height,
                            )
                            val perPixelX = painted.horizontalDegrees / (painted.bitmap.width * fit)
                            val perPixelY = painted.verticalDegrees / (painted.bitmap.height * fit)
                            detectDragGestures(
                                onDragStart = { start ->
                                    finger = start
                                    viewModel.beginPointOfViewDrag()
                                },
                                onDragEnd = {
                                    finger = null
                                    viewModel.endPointOfViewDrag()
                                },
                                onDragCancel = {
                                    finger = null
                                    viewModel.endPointOfViewDrag()
                                },
                            ) { change, drag ->
                                change.consume()
                                finger = change.position
                                // Il dito trascina l'immagine, non il punto di vista: il punto
                                // che si è toccato resta sotto il dito, e il centro gli va
                                // incontro. Spostare la foto a destra vuol dire guardare più a
                                // sinistra.
                                viewModel.dragPointOfView(
                                    panDegrees = -drag.x * perPixelX,
                                    tiltDegrees = drag.y * perPixelY,
                                )
                            }
                        },
                )
                // Il mirino: il punto di fuga è il centro della tela, e finché non si vede si
                // sta scegliendo alla cieca. Il cerchietto è il dito, che porta lì il suo punto.
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val arm = 16.dp.toPx()
                    val ring = 7.dp.toPx()
                    val stroke = 1.5.dp.toPx()
                    val ink = Luna.Ok
                    drawLine(ink, Offset(cx - arm, cy), Offset(cx - ring, cy), strokeWidth = stroke)
                    drawLine(ink, Offset(cx + ring, cy), Offset(cx + arm, cy), strokeWidth = stroke)
                    drawLine(ink, Offset(cx, cy - arm), Offset(cx, cy - ring), strokeWidth = stroke)
                    drawLine(ink, Offset(cx, cy + ring), Offset(cx, cy + arm), strokeWidth = stroke)
                    drawCircle(ink, radius = ring, center = Offset(cx, cy), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                    finger?.let {
                        drawCircle(
                            Color.White,
                            radius = 18.dp.toPx(),
                            center = it,
                            style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        )
                    }
                }
            }
        }

        // ---- Rollio: il primo comando sotto la foto, perché è quello che si corregge a occhio ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Rollio", style = MaterialTheme.typography.labelLarge, color = Luna.OnSurfaceDim)
            Slider(
                value = view.rollDegrees,
                onValueChange = viewModel::setPointOfViewRoll,
                valueRange = -10f..10f,
                steps = 79,
                modifier = Modifier.weight(1f),
            )
            Text(
                "%+.2f°".format(view.rollDegrees),
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
        }

        // ---- Proiezione: due righe da due, così ci stanno tutte e quattro ----
        Text("Proiezione", style = MaterialTheme.typography.labelLarge, color = Luna.OnSurfaceDim)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProjectionChip(null, view.projection, "Automatica", Modifier.weight(1f), viewModel)
            ProjectionChip(
                StitchProjection.EQUIRECTANGULAR, view.projection,
                "Sferica", Modifier.weight(1f), viewModel,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProjectionChip(
                StitchProjection.CYLINDRICAL, view.projection,
                "Cilindrica", Modifier.weight(1f), viewModel,
            )
            ProjectionChip(
                StitchProjection.MERCATOR, view.projection,
                "Mercatore", Modifier.weight(1f), viewModel,
            )
        }

        // ---- Fin dove sale la tela ----
        Text("Fin dove sale la tela", style = MaterialTheme.typography.labelLarge, color = Luna.OnSurfaceDim)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for (limit in intArrayOf(0, 55, 65, 75)) {
                FilterChip(
                    selected = view.verticalLimitDegrees.roundToInt() == limit,
                    onClick = { viewModel.setPointOfViewLimit(limit.toFloat()) },
                    label = {
                        Text(
                            if (limit == 0) "Tutto" else "$limit°",
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        // ---- I numeri: si leggono, non si toccano, e stanno sotto i comandi ----
        Text(
            "Centro pan %+.0f° · alto/basso %+.0f°%s".format(
                view.panDegrees, view.tiltDegrees,
                if (dragging) " · sto disegnando in fretta" else "",
            ),
            style = MaterialTheme.typography.labelMedium,
            color = Luna.OnSurfaceDim,
        )
        shape?.let {
            Text(
                "%s · tela fino a %.0f° · deformazione in cima ×%.1f in orizzontale, ×%.1f in verticale"
                    .format(shortName(it.projection), it.reachDegrees, it.horizontalStretch, it.verticalStretch),
                style = MaterialTheme.typography.labelMedium,
                color = Luna.OnSurfaceDim,
            )
        }

        // ---- Le decisioni ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            OutlinedButton(onClick = viewModel::resetPointOfView, modifier = Modifier.weight(1f)) {
                ButtonLabel(LunaIcons.Refresh, "Com'era")
            }
            OutlinedButton(onClick = viewModel::skipPointOfView, modifier = Modifier.weight(1f)) {
                ButtonLabel(LunaIcons.Panorama, "Decidi tu")
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (forJob != null) {
                Button(onClick = viewModel::savePointOfView, modifier = Modifier.weight(1f)) {
                    ButtonLabel(LunaIcons.Download, "Salva e chiudi")
                }
            }
            OutlinedButton(onClick = viewModel::confirmPointOfView, modifier = Modifier.weight(1f)) {
                ButtonLabel(LunaIcons.Panorama, "Cuci così")
            }
        }
    }
}

@Composable
private fun ProjectionChip(
    projection: StitchProjection?,
    chosen: StitchProjection?,
    label: String,
    modifier: Modifier,
    viewModel: MainViewModel,
) {
    FilterChip(
        selected = chosen == projection,
        onClick = { viewModel.setPointOfViewProjection(projection) },
        label = {
            Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        },
        modifier = modifier,
    )
}

/**
 * Il nome corto della proiezione, quello che sta su un pulsante.
 *
 * «Sferica» invece di «equirettangolare» perche` e` la parola che si usa parlando, e su una
 * riga di pulsanti larga come un telefono l'altra non ci sta. Il nome per esteso resta nel
 * verdetto dell'unione, dove c'e` spazio e dove serve essere precisi.
 */
private fun shortName(projection: StitchProjection): String = when (projection) {
    StitchProjection.EQUIRECTANGULAR -> "Sferica"
    StitchProjection.CYLINDRICAL -> "Cilindrica"
    StitchProjection.MERCATOR -> "Mercatore"
}
