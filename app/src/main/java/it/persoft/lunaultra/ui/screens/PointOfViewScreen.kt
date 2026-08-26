package it.persoft.lunaultra.ui.screens

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.stitch.StitchProjection
import it.persoft.lunaultra.ui.MainViewModel
import it.persoft.lunaultra.ui.components.ButtonLabel
import it.persoft.lunaultra.ui.components.Hint
import it.persoft.lunaultra.ui.components.LabeledValue
import it.persoft.lunaultra.ui.components.SectionCard
import it.persoft.lunaultra.ui.theme.Luna
import it.persoft.lunaultra.ui.theme.LunaIcons
import kotlin.math.roundToInt

/**
 * La fase intermedia: da dove guardare la panoramica.
 *
 * Una panoramica non ha un diritto e un rovescio. I fotogrammi stanno su una sfera, e stenderla
 * su un rettangolo deforma per forza: quanto e **dove** dipende da quale punto della sfera
 * finisce al centro. Lo stesso ramo che nell'equirettangolare centrata sull'orizzonte si allarga
 * cinque volte, portato al centro non si allarga affatto — e a pagare diventa qualcos'altro.
 *
 * Non è una scelta che si fa leggendo dei numeri: si fa guardando. Per questo qui c'è la
 * panoramica vera, in piccolo, e il dito la gira. I numeri sotto ci sono lo stesso, perché una
 * deformazione di cinque volte e mezza su una miniatura da seicento pixel non si vede, e su
 * quattordicimila sì.
 *
 * La cucitura a piena risoluzione è ferma ad aspettare mentre questa finestra è aperta. È il
 * motivo per cui si esce solo dai due tasti in fondo: uscire di lato lascerebbe un lavoro ad
 * aspettare per sempre una risposta che non arriva.
 */
@Composable
fun PointOfViewScreen(viewModel: MainViewModel) {
    val image by viewModel.pointOfViewImage.collectAsState()
    val view by viewModel.pointOfView.collectAsState()
    val shape by viewModel.pointOfViewShape.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Da dove guardarla", style = MaterialTheme.typography.titleLarge)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF101317)),
            contentAlignment = Alignment.Center,
        ) {
            val painted = image
            if (painted == null) {
                CircularProgressIndicator(modifier = Modifier.padding(48.dp))
            } else {
                // Il trascinamento è esatto per costruzione: l'anteprima dice quanti gradi
                // copre, quindi un dito che attraversa metà immagine gira la panoramica di
                // metà della sua larghezza in gradi. Cambiando proiezione o ritaglio i gradi
                // cambiano, e il rapporto si aggiorna da solo.
                Image(
                    bitmap = painted.bitmap.asImageBitmap(),
                    contentDescription = "Anteprima della panoramica",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(painted) {
                            val perPixelX = painted.horizontalDegrees / size.width.toFloat()
                            val perPixelY = painted.verticalDegrees / size.height.toFloat()
                            detectDragGestures { change, drag ->
                                change.consume()
                                // Il dito trascina l'immagine, non il punto di vista: spostare
                                // la foto a destra vuol dire guardare più a sinistra.
                                viewModel.dragPointOfView(
                                    panDegrees = -drag.x * perPixelX,
                                    tiltDegrees = drag.y * perPixelY,
                                )
                            }
                        },
                )
            }
        }

        SectionCard(title = "Come viene", icon = LunaIcons.Panorama, accent = Luna.Ok) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabeledValue(
                    "Centro spostato",
                    "pan %+.0f° · alto/basso %+.0f° · rollio %+.1f°".format(
                        view.panDegrees, view.tiltDegrees, view.rollDegrees,
                    ),
                )
                shape?.let {
                    LabeledValue("Proiezione", it.projection.label)
                    LabeledValue("La tela arriva a", "%.0f° dall'orizzonte".format(it.reachDegrees))
                    LabeledValue(
                        "Deformazione in cima",
                        "×%.1f in orizzontale · ×%.1f in verticale".format(
                            it.horizontalStretch, it.verticalStretch,
                        ),
                    )
                }
                Hint(
                    "Trascina l'immagine per spostare il centro. Quello che finisce al centro è " +
                        "la parte che non viene deformata: tutto il resto paga, e paga di più " +
                        "quanto più sta lontano.",
                )
            }
        }

        SectionCard(title = "Proiezione", icon = LunaIcons.Panorama, accent = Luna.PathLapse) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = view.projection == null,
                        onClick = { viewModel.setPointOfViewProjection(null) },
                        label = { Text("Automatica") },
                    )
                    for (projection in StitchProjection.entries) {
                        FilterChip(
                            selected = view.projection == projection,
                            onClick = { viewModel.setPointOfViewProjection(projection) },
                            label = { Text(projection.label) },
                        )
                    }
                }
                Hint(
                    "Tutte e tre tengono dritte le verticali e l'orizzonte. Cambia cosa cede in " +
                        "cima: l'equirettangolare allarga, la cilindrica allunga, la Mercatore " +
                        "ingrandisce uguale nei due sensi e le forme restano. «Automatica» " +
                        "sceglie da sola e ripiega sull'equirettangolare quando la tua " +
                        "stirerebbe troppo.",
                )
            }
        }

        SectionCard(title = "Fin dove sale la tela", icon = LunaIcons.Panorama, accent = Luna.Warn) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (limit in intArrayOf(0, 55, 65, 75)) {
                        FilterChip(
                            selected = view.verticalLimitDegrees.roundToInt() == limit,
                            onClick = { viewModel.setPointOfViewLimit(limit.toFloat()) },
                            label = { Text(if (limit == 0) "Tutto" else "$limit°") },
                        )
                    }
                }
                Hint(
                    "Tagliare il cielo più alto si guadagna due volte: sparisce la deformazione " +
                        "peggiore, e la panoramica viene più larga, perché la densità della tela " +
                        "la decide l'area totale e gli stessi pixel coprono meno cielo.",
                )
            }
        }

        SectionCard(title = "Rollio", icon = LunaIcons.Panorama, accent = Luna.PathLapse) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Slider(
                    value = view.rollDegrees,
                    onValueChange = { viewModel.setPointOfViewRoll(it) },
                    valueRange = -10f..10f,
                    steps = 39,
                )
                LabeledValue("Inclinazione", "%+.1f°".format(view.rollDegrees))
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = viewModel::resetPointOfView,
                modifier = Modifier.weight(1f),
            ) {
                ButtonLabel(LunaIcons.Refresh, "Com'era")
            }
            OutlinedButton(
                onClick = viewModel::skipPointOfView,
                modifier = Modifier.weight(1f),
            ) {
                ButtonLabel(LunaIcons.Panorama, "Decidi tu")
            }
            Button(
                onClick = viewModel::confirmPointOfView,
                modifier = Modifier.weight(1f),
            ) {
                ButtonLabel(LunaIcons.Panorama, "Cuci così")
            }
        }
        Hint(
            "La cucitura a piena risoluzione è ferma qui e aspetta: parte quando scegli. " +
                "«Decidi tu» va avanti come se questa finestra non ci fosse stata.",
        )
    }
}
