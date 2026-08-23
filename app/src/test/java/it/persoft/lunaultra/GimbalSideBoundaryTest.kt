package it.persoft.lunaultra

import it.persoft.lunaultra.gimbal.SELFIE_PAN_DEG
import it.persoft.lunaultra.gimbal.SIDE_BOUNDARY_DEG
import it.persoft.lunaultra.gimbal.recenterLandsAtPan
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Il ricentraggio va nella posizione di riposo più vicina, e le posizioni di riposo sono due.
 *
 * Fronte a 0°, selfie a 180°: il confine sta a metà strada, cioè a 90°, non a 180°. La corsa
 * del pan arriva a +235°, quindi tutta la parte destra della corsa è terreno dove «centro»
 * vuol dire selfie — ed è lì che una calibrazione si è persa, ricentrando dal fine corsa destro
 * e credendo di essere tornata a zero.
 */
class GimbalSideBoundaryTest {

    @Test
    fun `dentro il confine il ricentraggio e uno zero`() {
        assertEquals(0f, recenterLandsAtPan(0f), 0.001f)
        assertEquals(0f, recenterLandsAtPan(-57f), 0.001f)
        assertEquals(0f, recenterLandsAtPan(57f), 0.001f)
        assertEquals(0f, recenterLandsAtPan(SIDE_BOUNDARY_DEG), 0.001f)
    }

    @Test
    fun `oltre il confine il ricentraggio e un mezzo giro`() {
        assertEquals(SELFIE_PAN_DEG, recenterLandsAtPan(91f), 0.001f)
        assertEquals(SELFIE_PAN_DEG, recenterLandsAtPan(180f), 0.001f)
        // Il fine corsa destro: è da qui che il ricentraggio finiva nel selfie.
        assertEquals(SELFIE_PAN_DEG, recenterLandsAtPan(235f), 0.001f)
    }

    @Test
    fun `il confine e simmetrico anche se la corsa non ci arriva`() {
        assertEquals(-SELFIE_PAN_DEG, recenterLandsAtPan(-120f), 0.001f)
    }

    @Test
    fun `il fine corsa vicino resta il posto giusto da cui ricentrare`() {
        // Su entrambi gli assi il limite vicino sta a 57° dallo zero: dentro il confine con
        // 33° di margine, abbastanza perché anche una stima imprecisa non lo faccia sbagliare.
        assertEquals(0f, recenterLandsAtPan(-57f), 0.001f)
        assertEquals(0f, recenterLandsAtPan(-57f + 30f), 0.001f)
    }
}
