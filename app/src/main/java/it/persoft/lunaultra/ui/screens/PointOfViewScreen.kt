package it.persoft.lunaultra.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.stitch.PanoramaView
import it.persoft.lunaultra.stitch.StitchProjection
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.theme.Luna
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Quanto ci si puo` avvicinare all'anteprima. Quattro volte: oltre, di una panoramica larga
 * centottanta gradi se ne vede una fetta cosi` stretta che non si capisce piu` dove si sta
 * guardando, e il senso era controllare come stanno insieme le foto.
 */
private const val MAX_ZOOM = 4f

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

    // L'ingrandimento e di quanto l'immagine e` stata spostata sotto la lente.
    //
    // Serve a **controllare**, non a modificare: con la panoramica intera in trecento pixel
    // due foto attaccate male e due attaccate bene si somigliano. Il punto di fuga non cambia
    // di una virgola — quello che cambia e` quanto da vicino lo si guarda.
    var zoom by remember { mutableStateOf(1f) }
    var shift by remember { mutableStateOf(Offset.Zero) }

    // Il ritaglio si accende: finché è spento il dito sceglie il punto di fuga, acceso tira
    // il rettangolo. Due mestieri sullo stesso dito non possono convivere senza un
    // interruttore — è la stessa ragione per cui una matita e una gomma non sono lo stesso
    // oggetto.
    var cropping by remember { mutableStateOf(false) }

    /**
     * L'ultima anteprima disegnata, letta **dentro** il gesto invece che dall'esterno.
     *
     * Era questo il trascinamento a scatti, e non aveva niente a che fare col gesto. Il
     * rilevatore era legato all'immagine: ogni volta che ne arrivava una nuova — cioe` a ogni
     * ridisegno, cioe` due o tre volte al secondo mentre il dito si muove — Compose lo smontava
     * e lo rimontava, e il gesto in corso moriva li`. Tre scatti e stop, con il dito ancora giu`
     * e nessuno piu` ad ascoltarlo.
     *
     * Legato a niente, il rilevatore vive quanto la schermata; l'immagine se la va a prendere
     * lui quando serve. Le misure che gli servono — quanti pixel e quanti gradi — cambiano solo
     * cambiando proiezione o ritaglio, mai a meta` di un trascinamento.
     */
    val latest = rememberUpdatedState(image)

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
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = shift.x,
                            translationY = shift.y,
                        )
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
                        .pointerInput(Unit) {
                            val slop = viewConfiguration.touchSlop
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val shot = latest.value ?: return@awaitEachGesture
                                val fit = min(
                                    size.width.toFloat() / shot.bitmap.width,
                                    size.height.toFloat() / shot.bitmap.height,
                                )
                                val drawnWidth = shot.bitmap.width * fit
                                val drawnHeight = shot.bitmap.height * fit
                                val left = (size.width - drawnWidth) / 2f
                                val top = (size.height - drawnHeight) / 2f
                                val perPixelX = shot.horizontalDegrees / drawnWidth
                                val perPixelY = shot.verticalDegrees / drawnHeight
                                val centre = Offset(size.width / 2f, size.height / 2f)

                                /** Dal pixel dello schermo al pixel dell'immagine come sarebbe senza ingrandimento. */
                                fun unzoom(point: Offset): Offset =
                                    (point - centre - shift) / zoom + centre

                                /** L'immagine non si porta via: si ferma quando il suo bordo arriva al bordo. */
                                fun clamp(offset: Offset): Offset {
                                    val slackX = (drawnWidth * zoom - size.width).coerceAtLeast(0f) / 2f
                                    val slackY = (drawnHeight * zoom - size.height).coerceAtLeast(0f) / 2f
                                    return Offset(
                                        offset.x.coerceIn(-slackX, slackX),
                                        offset.y.coerceIn(-slackY, slackY),
                                    )
                                }

                                // Col ritaglio acceso il dito fa un altro mestiere: prende
                                // l'angolo più vicino, o sposta tutto il rettangolo se il
                                // dito è caduto dentro. Il punto di fuga non si tocca.
                                if (cropping) {
                                    val rect = cropRect(view, left, top, drawnWidth, drawnHeight)
                                    val start = unzoom(down.position)
                                    // Il raggio di presa non scala con l'ingrandimento: un
                                    // pollice resta un pollice, e ingrandendo la maniglia
                                    // deve restare della stessa taglia sotto il dito.
                                    val grabbed = grabOf(start, rect, GRAB_DP.dp.toPx() / zoom)
                                    var current = rect
                                    var lastAt = start
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) break
                                        change.consume()
                                        val now = unzoom(change.position)
                                        current = dragCrop(current, grabbed, now - lastAt, left, top, drawnWidth, drawnHeight)
                                        lastAt = now
                                        viewModel.setPointOfViewCrop(
                                            left = (current.left - left) / drawnWidth,
                                            top = (current.top - top) / drawnHeight,
                                            right = (current.right - left) / drawnWidth,
                                            bottom = (current.bottom - top) / drawnHeight,
                                        )
                                    }
                                    return@awaitEachGesture
                                }

                                finger = unzoom(down.position)
                                viewModel.beginPointOfViewDrag()
                                var last = down.position
                                var travelled = 0f
                                var pinching = false
                                var lastSpan = 0f
                                var lastMiddle = Offset.Zero
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val down2 = event.changes.filter { it.pressed }
                                    if (down2.size >= 2) {
                                        // Due dita: si guarda da vicino. Il punto di fuga non
                                        // si tocca — con due dita sullo schermo nessuno sta
                                        // scegliendo dove guardare, sta guardando meglio.
                                        val a = down2[0].position
                                        val b = down2[1].position
                                        val span = (a - b).getDistance()
                                        val middle = (a + b) / 2f
                                        if (!pinching) {
                                            pinching = true
                                            travelled = Float.MAX_VALUE
                                            finger = null
                                        } else if (lastSpan > 1f) {
                                            val was = zoom
                                            zoom = (zoom * span / lastSpan).coerceIn(1f, MAX_ZOOM)
                                            // Il punto fra le dita resta fra le dita: e` la
                                            // differenza fra ingrandire e far scappare via
                                            // quello che si stava guardando.
                                            shift = clamp(
                                                (shift + middle - lastMiddle) * (zoom / was) +
                                                    (middle - centre) * (1f - zoom / was),
                                            )
                                            viewModel.setPointOfViewZoom(zoom, settled = false)
                                        }
                                        lastSpan = span
                                        lastMiddle = middle
                                        down2.forEach { it.consume() }
                                        continue
                                    }
                                    if (pinching) {
                                        if (down2.isEmpty()) break
                                        // Un dito solo dopo il pizzico: si riparte da capo,
                                        // altrimenti lo scarto fra le due posizioni diventa
                                        // uno strappo.
                                        last = down2.first().position
                                        lastSpan = 0f
                                        continue
                                    }
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (!change.pressed) break
                                    val step = change.position - last
                                    last = change.position
                                    travelled += step.getDistance()
                                    finger = unzoom(change.position)
                                    if (travelled > slop) {
                                        change.consume()
                                        // Il dito trascina l'immagine, non il punto di vista:
                                        // il punto toccato resta sotto il dito e il centro gli
                                        // va incontro. Ingrandendo, lo stesso pixel di schermo
                                        // vale meno gradi: e` questo che rende il movimento
                                        // fine quando serve fine.
                                        viewModel.dragPointOfView(
                                            panDegrees = -step.x * perPixelX / zoom,
                                            tiltDegrees = step.y * perPixelY / zoom,
                                        )
                                    }
                                }
                                finger = null
                                viewModel.endPointOfViewDrag()
                                if (pinching) {
                                    if (zoom <= 1.01f) {
                                        zoom = 1f
                                        shift = Offset.Zero
                                    }
                                    viewModel.setPointOfViewZoom(zoom, settled = true)
                                } else if (travelled <= slop) {
                                    // Solo dentro l'immagine. Una panoramica alta e stretta
                                    // lascia due fasce nere ai lati, e li` il conto dei gradi
                                    // continuava lo stesso: un dito appoggiato nel nero valeva
                                    // ottanta gradi e la voltava per intero.
                                    val where = unzoom(last)
                                    val inside = where.x >= left && where.x <= left + drawnWidth &&
                                        where.y >= top && where.y <= top + drawnHeight
                                    if (inside) {
                                        viewModel.placePointOfView(
                                            panDegrees = (where.x - centre.x) * perPixelX,
                                            tiltDegrees = -(where.y - centre.y) * perPixelY,
                                        )
                                    }
                                }
                            }
                        },
                )
                // Il mirino: il punto di fuga è il centro della tela, e finché non si vede si
                // sta scegliendo alla cieca. Il cerchietto è il dito, che porta lì il suo punto.
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        // Lo stesso ingrandimento dell'immagine, se no il mirino e il
                        // rettangolo restano fermi mentre la foto sotto si sposta — e
                        // indicherebbero un punto che non e` quello.
                        .graphicsLayer(
                            scaleX = zoom,
                            scaleY = zoom,
                            translationX = shift.x,
                            translationY = shift.y,
                        ),
                ) {
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    // I segni restano della stessa taglia sullo schermo: ingranditi insieme
                    // all'immagine diventerebbero pennellate.
                    val arm = 16.dp.toPx() / zoom
                    val ring = 7.dp.toPx() / zoom
                    val stroke = 1.5.dp.toPx() / zoom
                    val ink = Luna.Ok
                    drawLine(ink, Offset(cx - arm, cy), Offset(cx - ring, cy), strokeWidth = stroke)
                    drawLine(ink, Offset(cx + ring, cy), Offset(cx + arm, cy), strokeWidth = stroke)
                    drawLine(ink, Offset(cx, cy - arm), Offset(cx, cy - ring), strokeWidth = stroke)
                    drawLine(ink, Offset(cx, cy + ring), Offset(cx, cy + arm), strokeWidth = stroke)
                    drawCircle(ink, radius = ring, center = Offset(cx, cy), style = Stroke(stroke))
                    finger?.let {
                        drawCircle(
                            Color.White,
                            radius = 18.dp.toPx() / zoom,
                            center = it,
                            style = Stroke(stroke),
                        )
                    }

                    // Il rettangolo del ritaglio, con fuori l'ombra di quello che si butta.
                    // Vederlo scuro è ciò che rende la scelta una scelta: un rettangolo
                    // disegnato sopra un'immagine tutta uguale non dice cosa stai perdendo.
                    if (view.cropped || cropping) {
                        val painted = latest.value
                        if (painted != null) {
                            val fit = min(
                                size.width / painted.bitmap.width,
                                size.height / painted.bitmap.height,
                            )
                            val dw = painted.bitmap.width * fit
                            val dh = painted.bitmap.height * fit
                            val dl = (size.width - dw) / 2f
                            val dt = (size.height - dh) / 2f
                            val rect = Rect(
                                dl + view.cropLeft * dw,
                                dt + view.cropTop * dh,
                                dl + view.cropRight * dw,
                                dt + view.cropBottom * dh,
                            )
                            // Le quattro fasce buttate via, in trasparenza.
                            val shade = Color.Black.copy(alpha = 0.55f)
                            drawRect(shade, Offset(dl, dt), Size(dw, rect.top - dt))
                            drawRect(shade, Offset(dl, rect.bottom), Size(dw, dt + dh - rect.bottom))
                            drawRect(shade, Offset(dl, rect.top), Size(rect.left - dl, rect.height))
                            drawRect(shade, Offset(rect.right, rect.top), Size(dl + dw - rect.right, rect.height))
                            val edge = if (cropping) Luna.Ok else Luna.OnSurfaceDim
                            drawRect(
                                edge,
                                topLeft = Offset(rect.left, rect.top),
                                size = Size(rect.width, rect.height),
                                style = Stroke(stroke * 1.5f),
                            )
                            if (cropping) {
                                // Gli angoli si vedono, se no non si sa dove mettere il dito.
                                val armLength = 22.dp.toPx() / zoom
                                listOf(
                                    Offset(rect.left, rect.top) to Offset(1f, 1f),
                                    Offset(rect.right, rect.top) to Offset(-1f, 1f),
                                    Offset(rect.left, rect.bottom) to Offset(1f, -1f),
                                    Offset(rect.right, rect.bottom) to Offset(-1f, -1f),
                                ).forEach { (corner, way) ->
                                    drawLine(
                                        edge,
                                        corner,
                                        Offset(corner.x + way.x * armLength, corner.y),
                                        strokeWidth = stroke * 3f,
                                    )
                                    drawLine(
                                        edge,
                                        corner,
                                        Offset(corner.x, corner.y + way.y * armLength),
                                        strokeWidth = stroke * 3f,
                                    )
                                }
                            }
                        }
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
            FilterChip(
                selected = cropping,
                onClick = {
                    cropping = !cropping
                    // Accendendolo la prima volta il rettangolo parte da tutta la tela: si
                    // stringe da lì, che è il verso in cui si ragiona guardando una foto.
                    if (cropping && !view.cropped) viewModel.setPointOfViewCrop(0f, 0f, 1f, 1f)
                },
                label = {
                    Text(
                        "Ritaglia",
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Luna.Ok.copy(alpha = 0.20f),
                    selectedLabelColor = Luna.Ok,
                ),
                modifier = Modifier.weight(1.2f),
            )
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
                if (zoom > 1.01f) append(" · ingrandita ×%.1f".format(zoom))
                if (view.cropped) {
                    append(" · ritaglio %.0f%% × %.0f%%".format(
                        (view.cropRight - view.cropLeft) * 100,
                        (view.cropBottom - view.cropTop) * 100,
                    ))
                }
                shape?.let {
                    append(" · %s fino a %.0f° · cima ×%.1f↔ ×%.1f↕".format(
                        shortName(it.projection), it.reachDegrees, it.horizontalStretch, it.verticalStretch,
                    ))
                }
            },
            style = MaterialTheme.typography.labelSmall,
            color = if (zoom > 1.01f) Luna.Ok else Luna.OnSurfaceDim,
            modifier = Modifier.clickable(enabled = zoom > 1.01f) {
                // Tornare indietro deve costare un tocco: con due dita si arriva a ×4 in
                // mezzo secondo, e uscirne a pizzichi e` sempre piu` lento che entrarci.
                zoom = 1f
                shift = Offset.Zero
                viewModel.setPointOfViewZoom(1f, settled = true)
            },
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

/** Quale parte del rettangolo ha preso il dito. */
private enum class Grab { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, INSIDE, NONE }

/** Quanto e` largo il bersaglio di un angolo, in dp: un polpastrello, non un pixel. */
private const val GRAB_DP = 28

/** Il rettangolo del ritaglio in pixel di schermo, dalle frazioni salvate. */
private fun cropRect(view: PanoramaView, left: Float, top: Float, width: Float, height: Float) = Rect(
    left + view.cropLeft * width,
    top + view.cropTop * height,
    left + view.cropRight * width,
    top + view.cropBottom * height,
)

/**
 * Cosa ha preso il dito: l'angolo più vicino, o tutto il rettangolo se è caduto dentro.
 *
 * Gli angoli vincono sul dentro anche quando il dito è dentro: un angolo è un bersaglio
 * piccolo e chi lo cerca lo cerca apposta, mentre il dentro è grande e ci si finisce per caso.
 */
private fun grabOf(point: Offset, rect: Rect, radius: Float): Grab {
    val corners = listOf(
        Grab.TOP_LEFT to Offset(rect.left, rect.top),
        Grab.TOP_RIGHT to Offset(rect.right, rect.top),
        Grab.BOTTOM_LEFT to Offset(rect.left, rect.bottom),
        Grab.BOTTOM_RIGHT to Offset(rect.right, rect.bottom),
    )
    val nearest = corners.minByOrNull { (_, corner) -> (point - corner).getDistance() }
    if (nearest != null && (point - nearest.second).getDistance() <= radius) return nearest.first
    return if (rect.contains(point)) Grab.INSIDE else Grab.NONE
}

/**
 * Il rettangolo dopo che il dito si è mosso di [step], senza uscire dall'immagine.
 *
 * Spostandolo tutto intero il rettangolo si ferma al bordo invece di deformarsi: è la
 * differenza fra spostare una cornice e stiracchiarla. Tirando un angolo, l'angolo opposto
 * resta fermo — perché è quello che si vede fare a una cornice, e perché altrimenti il
 * rettangolo scappa via mentre lo si stringe.
 */
private fun dragCrop(
    rect: Rect,
    grab: Grab,
    step: Offset,
    left: Float,
    top: Float,
    width: Float,
    height: Float,
): Rect {
    val right = left + width
    val bottom = top + height
    val minSide = MIN_CROP_SHARE * min(width, height)
    fun x(value: Float, low: Float, high: Float) = value.coerceIn(low, high)
    return when (grab) {
        Grab.INSIDE -> {
            val dx = step.x.coerceIn(left - rect.left, right - rect.right)
            val dy = step.y.coerceIn(top - rect.top, bottom - rect.bottom)
            rect.translate(dx, dy)
        }
        Grab.TOP_LEFT -> Rect(
            x(rect.left + step.x, left, rect.right - minSide),
            x(rect.top + step.y, top, rect.bottom - minSide),
            rect.right,
            rect.bottom,
        )
        Grab.TOP_RIGHT -> Rect(
            rect.left,
            x(rect.top + step.y, top, rect.bottom - minSide),
            x(rect.right + step.x, rect.left + minSide, right),
            rect.bottom,
        )
        Grab.BOTTOM_LEFT -> Rect(
            x(rect.left + step.x, left, rect.right - minSide),
            rect.top,
            rect.right,
            x(rect.bottom + step.y, rect.top + minSide, bottom),
        )
        Grab.BOTTOM_RIGHT -> Rect(
            rect.left,
            rect.top,
            x(rect.right + step.x, rect.left + minSide, right),
            x(rect.bottom + step.y, rect.top + minSide, bottom),
        )
        Grab.NONE -> rect
    }
}

/** Sotto un decimo del lato il rettangolo non si stringe piu`: sarebbe un ritaglio per sbaglio. */
private const val MIN_CROP_SHARE = 0.1f
