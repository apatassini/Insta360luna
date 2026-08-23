package it.persoft.lunaultra

import it.persoft.lunaultra.gimbal.backoffPulses
import it.persoft.lunaultra.gimbal.effectivePulses
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La curva si misura contando impulsi, non leggendo un cronometro.
 *
 * L'impulso dura sempre uguale, quindi il numero di impulsi *è* la misura. Qui si controlla
 * l'aritmetica che ne discende: come si conta l'ultimo impulso, che si appoggia al limite a
 * metà strada, e quanto arretrare perché la misura di un'intensità lenta duri il giusto.
 */
class GimbalPulseCurveTest {

    @Test
    fun `l'ultimo impulso vale mezzo perche si appoggia al limite a meta strada`() {
        assertEquals(19.5f, effectivePulses(20), 0.0001f)
        assertEquals(0.5f, effectivePulses(1), 0.0001f)
    }

    @Test
    fun `un solo impulso non vale mai zero, altrimenti i gradi al secondo esploderebbero`() {
        assertEquals(0.5f, effectivePulses(0), 0.0001f)
        assertTrue(effectivePulses(-3) > 0f)
    }

    @Test
    fun `contare gli impulsi da i gradi per impulso, e il conto e coerente coi gradi al secondo`() {
        // 57° di corsa coperti in 20 impulsi: l'ultimo conta mezzo, quindi 19,5 impulsi utili.
        val degreesPerPulse = 57f / effectivePulses(20)
        assertEquals(2.923f, degreesPerPulse, 0.001f)
        // Con impulsi da 400 ms sono due impulsi e mezzo al secondo.
        val degreesPerSecond = degreesPerPulse / (400L / 1000f)
        assertEquals(7.308f, degreesPerSecond, 0.001f)
    }

    @Test
    fun `l'arretramento scala col rapporto fra le intensita`() {
        // Un impulso all'1% copre un quarantesimo di un impulso al 40%: per contarne venti
        // lenti basta staccarsi di mezzo impulso di riferimento, e mezzo si arrotonda a uno.
        assertEquals(1, backoffPulses(1, 40, targetSlowPulses = 20, maxBackoffPulses = 15))
        assertEquals(3, backoffPulses(5, 40, targetSlowPulses = 20, maxBackoffPulses = 15))
        assertEquals(5, backoffPulses(10, 40, targetSlowPulses = 20, maxBackoffPulses = 15))
    }

    @Test
    fun `l'arretramento non e mai zero ne oltre il tetto`() {
        assertEquals(1, backoffPulses(1, 1000, targetSlowPulses = 20, maxBackoffPulses = 15))
        assertEquals(15, backoffPulses(10, 5, targetSlowPulses = 20, maxBackoffPulses = 15))
        assertEquals(1, backoffPulses(5, 0, targetSlowPulses = 20, maxBackoffPulses = 15))
        assertEquals(1, backoffPulses(0, 40, targetSlowPulses = 20, maxBackoffPulses = 15))
    }

    @Test
    fun `la misura lenta non dipende dalla stima dell'arretramento, solo da quanto vale davvero`() {
        // Il tratto di stacco vale gli impulsi di riferimento che l'hanno percorso, e quelli
        // sono già misurati: se la stima sbaglia cambia solo la durata della misura.
        val referenceDegreesPerPulse = 2.9f
        val backoff = backoffPulses(1, 40, targetSlowPulses = 20, maxBackoffPulses = 15)
        val gapDegrees = backoff * referenceDegreesPerPulse
        // Trentanove impulsi all'1% per rifare quel tratto: l'ultimo mezzo, 38,5 utili.
        val slowDegreesPerPulse = gapDegrees / effectivePulses(39)
        assertEquals(0.0753f, slowDegreesPerPulse, 0.0005f)
        // Il rapporto con l'intensità di riferimento resta plausibile: circa un quarantesimo.
        assertEquals(38.5f, referenceDegreesPerPulse / slowDegreesPerPulse, 0.5f)
    }

    @Test
    fun `il delta del ritorno allo zero e la differenza fra due conteggi di impulsi`() {
        // Andata: 20 impulsi dallo zero vero al fine corsa. Si torna indietro con gli stessi
        // 20 e si ricontano: se ne servono 22, lo zero raggiunto contando sta due impulsi
        // oltre lo zero vero, e due impulsi sono l'errore in gradi.
        val outbound = 20
        val inbound = 22
        val degreesPerPulse = 57f / effectivePulses(outbound)
        val delta = inbound - outbound
        assertEquals(2, delta)
        assertEquals(5.846f, delta * degreesPerPulse, 0.001f)
    }

}
