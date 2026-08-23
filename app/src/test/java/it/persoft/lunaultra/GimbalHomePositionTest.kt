package it.persoft.lunaultra

import it.persoft.lunaultra.gimbal.homeDegreesFromTravel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * La casa si misura, non si integra.
 *
 * Due tempi comandati alla stessa intensità — da casa al primo fine corsa, e da un fine corsa
 * all'altro — bastano a dire dove sta la casa lungo la corsa: la velocità si semplifica nel
 * rapporto, ed è l'unica cosa che durante la ricerca dei fine corsa non si conosce ancora.
 */
class GimbalHomePositionTest {

    @Test
    fun `la casa a meta corsa esce a meta corsa`() {
        assertEquals(89f, homeDegreesFromTravel(-57f, 235f, 5_000L, 10_000L), 0.01f)
    }

    @Test
    fun `la casa appoggiata al primo fine corsa resta li`() {
        assertEquals(-57f, homeDegreesFromTravel(-57f, 235f, 0L, 10_000L), 0.01f)
    }

    /**
     * Il caso del log: giù in 2,7 s e su in 7,5 s dice che la casa era poco sopra l'orizzonte,
     * non a 75° come diceva l'integrazione con le velocità di ripiego.
     */
    @Test
    fun `il caso reale del tilt colloca la casa vicino all'orizzonte`() {
        val deg = homeDegreesFromTravel(-57f, 120f, 2_700L, 7_500L)
        assertEquals(6.7f, deg, 0.5f)
    }

    @Test
    fun `un tempo di corsa nullo non produce una posizione inventata`() {
        assertEquals(0f, homeDegreesFromTravel(-57f, 235f, 3_000L, 0L), 0.001f)
    }

    @Test
    fun `oltre la corsa intera la frazione resta dentro i limiti`() {
        assertEquals(235f, homeDegreesFromTravel(-57f, 235f, 99_000L, 10_000L), 0.01f)
    }
}
