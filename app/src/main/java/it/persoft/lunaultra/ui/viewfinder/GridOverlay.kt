package it.persoft.lunaultra.ui.viewfinder

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import it.persoft.lunaultra.ui.theme.Luna

/**
 * Griglia dei terzi e mirino centrale sopra l'anteprima.
 *
 * Serve a due cose diverse: comporre l'inquadratura, come su qualunque camera, e ritrovare il
 * centro quando si memorizza un punto — due punti composti con lo stesso riferimento danno un
 * movimento pulito, due punti «a occhio» no.
 */
@Composable
fun GridOverlay(modifier: Modifier = Modifier, showCenter: Boolean = true) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val lineColor = Color.White.copy(alpha = 0.22f)
        val width = size.width
        val height = size.height
        val stroke = 1.dp.toPx()

        for (i in 1..2) {
            val x = width * i / 3f
            val y = height * i / 3f
            drawLine(lineColor, Offset(x, 0f), Offset(x, height), stroke)
            drawLine(lineColor, Offset(0f, y), Offset(width, y), stroke)
        }

        if (showCenter) {
            val center = Offset(width / 2f, height / 2f)
            val arm = 14.dp.toPx()
            val markColor = Luna.Accent.copy(alpha = 0.75f)
            val markStroke = 1.5.dp.toPx()
            drawLine(markColor, center - Offset(arm, 0f), center - Offset(arm / 3f, 0f), markStroke)
            drawLine(markColor, center + Offset(arm / 3f, 0f), center + Offset(arm, 0f), markStroke)
            drawLine(markColor, center - Offset(0f, arm), center - Offset(0f, arm / 3f), markStroke)
            drawLine(markColor, center + Offset(0f, arm / 3f), center + Offset(0f, arm), markStroke)
        }
    }
}
