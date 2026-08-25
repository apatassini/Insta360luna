package it.persoft.lunaultra

import it.persoft.lunaultra.stitch.FeatureMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * L'abbinamento dei dettagli, che è la parte su cui si regge tutto il resto.
 *
 * Va verificata qui e non sul telefono, perché sul telefono l'unica risposta è «l'albero è
 * fuori posto» — che non dice se il difetto è nel trovare i dettagli, nell'abbinarli o nel
 * votare. Qui lo spostamento vero lo decido io, e chiedo che venga ritrovato.
 */
class FeatureMatcherTest {

    /** Una finta foto: macchie chiare e scure sparse, cioè qualcosa da riconoscere. */
    private fun scene(width: Int, height: Int, seed: Long): ByteArray {
        val gray = ByteArray(width * height)
        val random = Random(seed)
        // Fondo grigio con una lieve pendenza, come un cielo.
        for (y in 0 until height) {
            for (x in 0 until width) {
                gray[y * width + x] = (110 + y * 30 / height).toByte()
            }
        }
        repeat(600) {
            val cx = random.nextInt(width)
            val cy = random.nextInt(height)
            val radius = 2 + random.nextInt(4)
            val tone = if (random.nextBoolean()) 60 + random.nextInt(40) else 190 + random.nextInt(50)
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    if (dx * dx + dy * dy > radius * radius) continue
                    val x = cx + dx
                    val y = cy + dy
                    if (x < 0 || y < 0 || x >= width || y >= height) continue
                    gray[y * width + x] = tone.toByte()
                }
            }
        }
        return gray
    }

    /** La stessa scena traslata: quello che succede fra due foto vicine di una panoramica. */
    private fun shifted(source: ByteArray, width: Int, height: Int, dx: Int, dy: Int): ByteArray {
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sx = x - dx
                val sy = y - dy
                out[y * width + x] = if (sx in 0 until width && sy in 0 until height) {
                    source[sy * width + sx]
                } else {
                    120.toByte()
                }
            }
        }
        return out
    }

    private fun recover(dx: Int, dy: Int, seed: Long): Pair<Int, Int> {
        val width = 420
        val height = 320
        val fixed = scene(width, height, seed)
        val moving = shifted(fixed, width, height, dx, dy)
        val margin = FeatureMatcher.PATCH_RADIUS + 4
        val fixedPoints = FeatureMatcher.detect(
            fixed, width, height, margin, margin, width - margin, height - margin, cell = 24,
        )
        val movingPoints = FeatureMatcher.detect(
            moving, width, height, margin, margin, width - margin, height - margin, cell = 24,
        )
        assertTrue("servono dettagli da abbinare, trovati ${fixedPoints.size}", fixedPoints.size >= 40)
        val matches = FeatureMatcher.match(fixedPoints, movingPoints)
        assertTrue("troppo pochi abbinamenti: ${matches.size}", matches.size >= 20)
        // Il voto: la mediana degli scarti. Gli abbinamenti giusti concordano, gli sbagliati
        // cadono ognuno per conto suo e la mediana non se ne accorge.
        val xs = matches.map { it.movingX - it.fixedX }.sorted()
        val ys = matches.map { it.movingY - it.fixedY }.sorted()
        return xs[xs.size / 2] to ys[ys.size / 2]
    }

    @Test
    fun `ritrova uno spostamento piccolo`() {
        val (dx, dy) = recover(6, -4, seed = 1)
        // Un paio di pixel di tolleranza: le celle della griglia cadono in posti diversi nelle
        // due immagini, quindi non sempre il massimo locale è lo stesso pixel esatto. Quello
        // che conta è che la maggioranza indichi lo spostamento giusto.
        assertEquals(6f, dx.toFloat(), 2f)
        assertEquals(-4f, dy.toFloat(), 2f)
    }

    @Test
    fun `ritrova uno spostamento grande, che i punti di controllo non vedrebbero`() {
        // Cinquanta pixel: molto oltre il raggio in cui cerca la rifinitura, ed è esattamente
        // il caso dell'albero fuori posto di duecento pixel sull'originale.
        val (dx, dy) = recover(50, -37, seed = 2)
        assertEquals(50f, dx.toFloat(), 2f)
        assertEquals(-37f, dy.toFloat(), 2f)
    }

    @Test
    fun `su due scene diverse non inventa un accordo`() {
        val width = 420
        val height = 320
        val margin = FeatureMatcher.PATCH_RADIUS + 4
        val a = FeatureMatcher.detect(
            scene(width, height, 11), width, height, margin, margin, width - margin, height - margin, 24,
        )
        val b = FeatureMatcher.detect(
            scene(width, height, 77), width, height, margin, margin, width - margin, height - margin, 24,
        )
        val matches = FeatureMatcher.match(a, b)
        // Qualche abbinamento casuale può sempre uscire; quello che non deve uscire è un
        // gruppo concorde, perché è quello che il voto scambierebbe per una misura.
        val agreeing = matches.count { abs(it.movingX - it.fixedX) < 3 && abs(it.movingY - it.fixedY) < 3 }
        assertTrue("scene diverse non devono concordare: $agreeing su ${matches.size}", agreeing < 12)
    }

    private fun abs(value: Int) = if (value < 0) -value else value
}
