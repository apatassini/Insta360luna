package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.stitch.StitchProjection
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.theme.Luna
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
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                        // Un gesto solo per due mestieri, e non due che si contendono il dito.
                        //
                        // Tocco e trascinamento erano due rilevatori separati, e litigavano:
                        // il primo aspetta di capire se il dito si alza o si muove, e nel
                        // frattempo il secondo perdeva il filo — l'immagine faceva un salto e
                        // si fermava li`, e bisognava rialzare il dito e ripartire. A colpi.
                        //
                        // Qui il gesto si legge a mano, una volta sola: si segue il dito finche`
                        // resta giu`, e al suo alzarsi si guarda quanta strada ha fatto. Poca:
                        // era un tocco, e il punto va al centro. Tanta: era un trascinamento, e
                        // il centro l'ha gia` seguito passo per passo.
                        .pointerInput(painted) {
                            val fit = min(
                                size.width.toFloat() / painted.bitmap.width,
                                size.height.toFloat() / painted.bitmap.height,
                            )
                            val drawnWidth = painted.bitmap.width * fit
                            val drawnHeight = painted.bitmap.height * fit
                            val left = (size.width - drawnWidth) / 2f
                            val top = (size.height - drawnHeight) / 2f
                            val perPixelX = painted.horizontalDegrees / drawnWidth
                            val perPixelY = painted.verticalDegrees / drawnHeight
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                finger = down.position
                                viewModel.beginPointOfViewDrag()
                                var last = down.position
                                var travelled = 0f
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val step = change.position - last
                                    last = change.position
                                    travelled += step.getDistance()
                                    finger = change.position
                                    if (travelled > slop) {
                                        change.consume()
                                        // Il dito trascina l'immagine, non il punto di vista:
                                        // il punto toccato resta sotto il dito e il centro gli
                                        // va incontro.
                                        viewModel.dragPointOfView(
                                            panDegrees = -step.x * perPixelX,
                                            tiltDegrees = step.y * perPixelY,
                                        )
                                    }
                                }
                                finger = null
                                viewModel.endPointOfViewDrag()
                                if (travelled <= slop) {
                                    // Solo dentro l'immagine. Una panoramica alta e stretta
                                    // lascia due fasce nere ai lati, e li` il conto dei gradi
                                    // continuava lo stesso: un dito appoggiato nel nero valeva
                                    // ottanta gradi e la voltava per intero.
                                    val where = last
                                    val inside = where.x >= left && where.x <= left + drawnWidth &&
                                        where.y >= top && where.y <= top + drawnHeight
                                    if (inside) {
                                        viewModel.placePointOfView(
                                            panDegrees = (where.x - size.width / 2f) * perPixelX,
                                            tiltDegrees = -(where.y - size.height / 2f) * perPixelY,
                                        )
                                    }
                                }
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
                    drawCircle(ink, radius = ring, center = Offset(cx, cy), style = Stroke(stroke))
                    finger?.let {
                        drawCircle(
                            Color.White,
                            radius = 18.dp.toPx(),
                            center = it,
                            style = Stroke(stroke),
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

        // ---- Proiezione e ritaglio: una riga per uno, senza titoli sopra ----
        //
        // I titoli mangiavano due righe per dire quello che i pulsanti dicono da soli, e ogni
        // riga tolta e` spazio che va alla foto — che e` la sola cosa che qui serve guardare.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ProjectionChip(null, view.projection, "Auto", Modifier.weight(1f), viewModel)
            ProjectionChip(
                StitchProjection.EQUIRECTANGULAR, view.projection,
                "Sferica", Modifier.weight(1f), viewModel,
            )
            ProjectionChip(
                StitchProjection.CYLINDRICAL, view.projection,
                "Cilindrica", Modifier.weight(1f), viewModel,
            )
            ProjectionChip(
                StitchProjection.MERCATOR, view.projection,
                "Mercatore", Modifier.weight(1f), viewModel,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Tela", style = MaterialTheme.typography.labelMedium, color = Luna.OnSurfaceDim)
            for (limit in intArrayOf(0, 55, 65, 75)) {
                FilterChip(
                    selected = view.verticalLimitDegrees.roundToInt() == limit,
                    onClick = { viewModel.setPointOfViewLimit(limit.toFloat()) },
                    label = {
                        Text(
                            if (limit == 0) "Tutta" else "$limit°",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
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
            buildString {
                append("Tocca per centrare · pan %+.0f° · su/giù %+.0f°".format(view.panDegrees, view.tiltDegrees))
                shape?.let {
                    append(" · %s fino a %.0f° · cima ×%.1f↔ ×%.1f↕".format(
                        shortName(it.projection), it.reachDegrees, it.horizontalStretch, it.verticalStretch,
                    ))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = Luna.OnSurfaceDim,
        )

        // ---- Le decisioni, su una riga sola: la seconda era spazio tolto alla foto ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::resetPointOfView,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Com'era", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            OutlinedButton(
                onClick = viewModel::skipPointOfView,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Decidi tu", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
            if (forJob != null) {
                Button(
                    onClick = viewModel::savePointOfView,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Salva", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
            OutlinedButton(
                onClick = viewModel::confirmPointOfView,
                contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                modifier = Modifier.weight(1f),
            ) {
                Text("Cuci", style = MaterialTheme.typography.labelMedium, maxLines = 1)
            }
        }
    }
}

/**
 * Il pulsante di una proiezione: il disegno di quello che fa, non il suo nome.
 *
 * Quattro nomi lunghi su una riga di telefono si troncano tutti, e un nome troncato non dice
 * niente piu` di un disegno — dice meno. La forma invece si riconosce a colpo d'occhio: la sfera
 * e` una sfera, il cilindro e` un cilindro, la carta di Mercatore e` una carta a griglia. Il
 * nome per esteso resta sotto, nella riga dei numeri, dove si legge quello scelto.
 */
@Composable
private fun ProjectionChip(
    projection: StitchProjection?,
    chosen: StitchProjection?,
    label: String,
    modifier: Modifier,
    viewModel: MainViewModel,
) {
    val selected = chosen == projection
    FilterChip(
        selected = selected,
        onClick = { viewModel.setPointOfViewProjection(projection) },
        label = {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ProjectionGlyph(
                    projection = projection,
                    tint = if (selected) Luna.Ok else Luna.OnSurfaceDim,
                    modifier = Modifier.size(26.dp),
                )
            }
        },
        modifier = modifier.semantics { contentDescription = label },
    )
}

/**
 * I quattro disegni, fatti a mano con quattro righe di geometria.
 *
 * Nessun repertorio di icone ha un cilindro e una carta di Mercatore, e cercarne di simili
 * avrebbe voluto dire disegni che *quasi* dicono la cosa giusta. Questi la dicono esatta, e
 * costano meno di un file.
 */
@Composable
private fun ProjectionGlyph(projection: StitchProjection?, tint: Color, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val line = 1.6.dp.toPx()
        val stroke = Stroke(line)
        val pad = w * 0.14f
        when (projection) {
            // Automatica: due frecce che si scambiano il posto — la scelta la fa lei.
            null -> {
                val cx = w / 2f
                val cy = h / 2f
                val r = (w / 2f - pad)
                drawArc(
                    color = tint, startAngle = 40f, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(cx - r, cy - r), size = Size(r * 2, r * 2), style = stroke,
                )
                drawCircle(tint, radius = line * 1.4f, center = Offset(cx + r * 0.72f, cy + r * 0.72f))
            }
            // Sferica: un globo, con un meridiano e l'equatore.
            StitchProjection.EQUIRECTANGULAR -> {
                val cx = w / 2f
                val cy = h / 2f
                val r = w / 2f - pad
                drawCircle(tint, radius = r, center = Offset(cx, cy), style = stroke)
                drawLine(tint, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = line)
                drawOval(
                    color = tint,
                    topLeft = Offset(cx - r * 0.45f, cy - r),
                    size = Size(r * 0.9f, r * 2),
                    style = stroke,
                )
            }
            // Cilindrica: un cilindro in piedi, col coperchio in prospettiva.
            StitchProjection.CYLINDRICAL -> {
                val left = pad
                val right = w - pad
                val top = pad * 1.3f
                val bottom = h - pad * 1.3f
                val lid = (bottom - top) * 0.22f
                drawOval(
                    color = tint, topLeft = Offset(left, top),
                    size = Size(right - left, lid), style = stroke,
                )
                drawLine(tint, Offset(left, top + lid / 2), Offset(left, bottom - lid / 2), strokeWidth = line)
                drawLine(tint, Offset(right, top + lid / 2), Offset(right, bottom - lid / 2), strokeWidth = line)
                drawArc(
                    color = tint, startAngle = 0f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(left, bottom - lid),
                    size = Size(right - left, lid), style = stroke,
                )
            }
            // Mercatore: la carta nautica, un rettangolo a griglia.
            StitchProjection.MERCATOR -> {
                val left = pad
                val right = w - pad
                val top = pad * 1.5f
                val bottom = h - pad * 1.5f
                drawRect(
                    color = tint, topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top), style = stroke,
                )
                val stepX = (right - left) / 3f
                val stepY = (bottom - top) / 3f
                for (i in 1..2) {
                    drawLine(tint, Offset(left + stepX * i, top), Offset(left + stepX * i, bottom), strokeWidth = line * 0.7f)
                    drawLine(tint, Offset(left, top + stepY * i), Offset(right, top + stepY * i), strokeWidth = line * 0.7f)
                }
            }
        }
    }
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
