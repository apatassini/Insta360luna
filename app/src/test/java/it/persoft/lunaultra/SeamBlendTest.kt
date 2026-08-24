package it.persoft.lunaultra

import it.persoft.lunaultra.stitch.seamAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La fusione vive solo sulla cucitura: è la regola che elimina i fantasmi.
 *
 * La prima panoramica riuscita aveva tronchi semitrasparenti e sdraio sdoppiate, perché ogni
 * pixel della sovrapposizione era una media di due scatti: venti gradi di doppia esposizione.
 * Con la cucitura, un pixel appartiene a uno scatto solo appena il suo fotogramma domina, e la
 * media sopravvive soltanto in una striscia stretta dove i due si equivalgono.
 */
class SeamBlendTest {

    @Test
    fun `sulla cucitura esatta si fonde a meta'`() {
        assertEquals(0.5f, seamAlpha(0.4f, 0.4f), 1e-4f)
    }

    @Test
    fun `con un vantaggio netto il pixel e' di un solo fotogramma`() {
        // Dominanza ben oltre la morbidezza della cucitura: niente media, niente fantasma.
        assertEquals(1f, seamAlpha(0.6f, 0.3f), 1e-4f)
    }

    @Test
    fun `senza un secondo candidato il migliore prende tutto`() {
        assertEquals(1f, seamAlpha(0.8f, 0f), 1e-4f)
    }

    @Test
    fun `la transizione cresce con la dominanza senza scalini`() {
        val vicino = seamAlpha(0.42f, 0.40f)
        val medio = seamAlpha(0.45f, 0.40f)
        assertTrue(vicino in 0.5f..medio)
        assertTrue(medio in vicino..1f)
    }

    /** La striscia è stretta: al 15% di dominanza la fusione è già finita. */
    @Test
    fun `oltre la morbidezza la fusione e' gia' finita`() {
        // (0.575-0.425)/(0.575+0.425) = 0.15 esatto: il bordo della striscia.
        assertEquals(1f, seamAlpha(0.575f, 0.425f), 1e-3f)
    }
}
