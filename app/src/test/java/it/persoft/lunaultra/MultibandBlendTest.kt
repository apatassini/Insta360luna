package it.persoft.lunaultra

import it.persoft.lunaultra.stitch.MultibandBlender
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * La fusione multibanda: larga per i toni, netta per il dettaglio.
 *
 * È la proprietà che una striscia sola non può avere, ed è il motivo per cui esiste: fondere
 * tutto stretto lascia una riga di tono, fondere tutto largo stampa i fantasmi. Questi test
 * fissano i tre comportamenti che contano: lontano dalla cucitura ogni immagine resta sé
 * stessa, sulla cucitura non c'è nessuno scalino, e un'immagine fusa con sé stessa esce
 * intatta — se una banda si perdesse per strada, uscirebbe diversa.
 */
class MultibandBlendTest {

    private val width = 96
    private val height = 64

    private fun flat(value: Float) = Array(3) { FloatArray(width * height) { value } }

    /** Maschera a gradino verticale: sinistra alla base, destra al nuovo. */
    private fun stepMask(): FloatArray = FloatArray(width * height) { i ->
        if (i % width < width / 2) 0f else 1f
    }

    @Test
    fun `un'immagine fusa con se' stessa esce intatta`() {
        val gradient = Array(3) { FloatArray(width * height) { i -> (i % width) * 2f } }
        val result = MultibandBlender.blend(gradient, gradient, stepMask(), width, height)
        for (c in 0..2) {
            for (i in 0 until width * height) {
                assertEquals(gradient[c][i], result[c][i], 0.6f)
            }
        }
    }

    @Test
    fun `lontano dalla cucitura ogni lato resta il suo`() {
        val result = MultibandBlender.blend(flat(40f), flat(200f), stepMask(), width, height)
        // Prima colonna: tutta base. Ultima: tutta nuovo. La transizione sta nel mezzo.
        for (y in 0 until height) {
            assertEquals(40f, result[0][y * width], 2f)
            assertEquals(200f, result[0][y * width + width - 1], 2f)
        }
    }

    @Test
    fun `sulla cucitura la transizione e' morbida e monotona`() {
        val result = MultibandBlender.blend(flat(40f), flat(200f), stepMask(), width, height)
        val row = height / 2
        var previous = result[0][row * width]
        var maxStep = 0f
        for (x in 1 until width) {
            val value = result[0][row * width + x]
            assertTrue("La transizione torna indietro alla colonna $x", value >= previous - 1.5f)
            maxStep = maxOf(maxStep, abs(value - previous))
            previous = value
        }
        // Un gradino di 160 livelli fuso in multibanda non può saltare tutto in un pixel.
        assertTrue("Salto massimo di $maxStep livelli: è uno scalino, non una fusione", maxStep < 80f)
    }

    @Test
    fun `la transizione delle tinte e' larga decine di pixel`() {
        val result = MultibandBlender.blend(flat(40f), flat(200f), stepMask(), width, height)
        val row = height / 2
        // A un quarto e tre quarti della larghezza la fusione deve già farsi sentire un po':
        // è la banda larga che spalma i toni, quella che nasconde i salti di esposizione.
        val quarter = result[0][row * width + width / 4]
        val threeQuarter = result[0][row * width + 3 * width / 4]
        assertTrue("A sinistra la fusione non è arrivata ($quarter)", quarter > 40f + 1f)
        assertTrue("A destra la fusione non è arrivata ($threeQuarter)", threeQuarter < 200f - 1f)
    }

    @Test
    fun `su un ritaglio minuscolo la fusione degrada con grazia`() {
        val tiny = Array(3) { FloatArray(4) { 10f } }
        val tinyOver = Array(3) { FloatArray(4) { 90f } }
        val mask = floatArrayOf(0f, 0f, 1f, 1f)
        val result = MultibandBlender.blend(tiny, tinyOver, mask, 2, 2)
        assertEquals(10f, result[0][0], 0.01f)
        assertEquals(90f, result[0][2], 0.01f)
    }

    @Test
    fun `i livelli si adattano alla dimensione del ritaglio`() {
        assertEquals(1, MultibandBlender.levelsFor(4, 4))
        assertTrue(MultibandBlender.levelsFor(600, 1400) >= 5)
        assertTrue(MultibandBlender.levelsFor(600, 1400) <= MultibandBlender.SPLINE_LEVELS)
    }
}
