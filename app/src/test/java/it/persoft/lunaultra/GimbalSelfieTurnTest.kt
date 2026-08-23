package it.persoft.lunaultra

import it.persoft.lunaultra.gimbal.selfiePanTarget
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La corsa del pan è asimmetrica (-57°…+235°): il mezzo giro non può scegliere il verso a caso.
 * Sbagliarlo non dà errore, dà una camera che si ferma contro il fine corsa a metà strada.
 */
class GimbalSelfieTurnTest {

    private val minimum = -57f
    private val maximum = 235f

    @Test
    fun `dallo zero il mezzo giro va in avanti`() {
        assertEquals(180f, selfiePanTarget(0f, 180f, minimum, maximum), 0.01f)
    }

    @Test
    fun `oltre i 55 gradi in avanti non ci sta e si gira indietro`() {
        // 200 + 180 sfonderebbe il limite di 235: l'unico verso possibile è -180.
        assertEquals(20f, selfiePanTarget(200f, 180f, minimum, maximum), 0.01f)
    }

    @Test
    fun `quando nessuno dei due versi ci sta si va sul limite piu lontano`() {
        // Corsa stretta: da 10° in una corsa -20°…+40° nessun mezzo giro è intero.
        assertEquals(40f, selfiePanTarget(10f, 180f, -20f, 40f), 0.01f)
        assertEquals(-20f, selfiePanTarget(30f, 180f, -20f, 40f), 0.01f)
    }

    @Test
    fun `i limiti scambiati non cambiano il risultato`() {
        assertEquals(180f, selfiePanTarget(0f, 180f, maximum, minimum), 0.01f)
    }

    @Test
    fun `una posizione fuori corsa viene prima riportata dentro`() {
        // 400° viene riportato a 235°, e da lì l'unico mezzo giro possibile è all'indietro.
        assertEquals(55f, selfiePanTarget(400f, 180f, minimum, maximum), 0.01f)
    }
}
