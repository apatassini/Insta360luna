package it.persoft.lunaultra

import it.persoft.lunaultra.stitch.MemoryBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Il budget della tela, che decide quanto grande può venire una panoramica.
 *
 * È aritmetica pura e va verificata qui, perché sul telefono si verifica una volta sola: o
 * l'unione esce, o il sistema chiude l'applicazione e non resta nemmeno il log.
 */
class MemoryBudgetTest {

    private fun budget(
        availableMb: Long,
        thresholdMb: Long,
        low: Boolean = false,
    ) = MemoryBudget(
        javaMaxBytes = 512L * MB,
        javaUsedBytes = 120L * MB,
        nativeUsedBytes = 300L * MB,
        systemAvailableBytes = availableMb * MB,
        systemThresholdBytes = thresholdMb * MB,
        systemLow = low,
        measured = true,
    )

    @Test
    fun `la soglia del sistema e il margine restano fuori dal budget`() {
        // Tremila liberi, duecentocinquanta di soglia: restano 3000 − 250 − 512 = 2238, e se
        // ne prende il 70%.
        val canvas = budget(availableMb = 3000, thresholdMb = 250).canvasBytes
        assertEquals(1566L, canvas / MB)
    }

    @Test
    fun `su un telefono in affanno si chiede molto meno`() {
        val calm = budget(availableMb = 3000, thresholdMb = 250, low = false).canvasBytes
        val strained = budget(availableMb = 3000, thresholdMb = 250, low = true).canvasBytes
        assertTrue("in affanno deve chiedere meno", strained < calm / 2)
    }

    @Test
    fun `quando non resta niente il budget non diventa negativo`() {
        // Meno libero della sola soglia: la sottrazione andrebbe sotto zero, e una tela
        // negativa produrrebbe una densità immaginaria invece di un rifiuto onesto.
        val canvas = budget(availableMb = 100, thresholdMb = 400).canvasBytes
        assertTrue("mai negativo", canvas > 0L)
        assertEquals(128L, canvas / MB)
    }

    @Test
    fun `il ripiego senza contesto vale due volte la heap`() {
        // Chi non ha un Context — i test, e chiunque costruisca uno stitcher a mano — deve
        // ritrovare esattamente la vecchia stima prudente, non un numero nuovo per caso.
        val fallback = MemoryBudget(
            javaMaxBytes = 512L * MB,
            javaUsedBytes = 0L,
            nativeUsedBytes = 0L,
            systemAvailableBytes = (512L * MB * 2 / 0.70).toLong() + 512L * MB,
            systemThresholdBytes = 0L,
            systemLow = false,
            measured = false,
        )
        // Due volte la heap, a meno dell'arrotondamento dei megabyte.
        assertTrue(abs(fallback.canvasBytes - 2L * 512L * MB) < MB)
        assertTrue(fallback.describe().contains("prudenziale"))
    }

    private companion object {
        const val MB = 1024L * 1024L
    }
}
