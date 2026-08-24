package it.persoft.lunaultra

import it.persoft.lunaultra.camera.ExposureReading
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.timelapse.describeExposure
import it.persoft.lunaultra.timelapse.exposureGuardMillis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Quanto si aspetta dopo lo scatto prima di poter muovere il gimbal.
 *
 * La camera resta occupata cinque secondi per scatto, ma solo la posa vincola l'inquadratura:
 * quello che segue è compressione e scrittura, e il sensore è già stato letto. Sbagliare in
 * difetto significa foto mosse; sbagliare in eccesso significa buttare via il vantaggio. Il
 * riferimento è quello che la camera dichiara, con un tetto per quando non dichiara niente.
 */
class ExposureGuardTest {

    private fun auto(shutterSeconds: Double) = ExposureReading(
        program = LunaProtocolCodes.ExposureProgram.AUTO,
        iso = 400,
        shutterSeconds = shutterSeconds,
    )

    @Test
    fun `senza dato della camera si usa il tetto di due secondi`() {
        assertEquals(2_000L, exposureGuardMillis(null))
        assertEquals(2_000L, exposureGuardMillis(auto(0.0)))
    }

    @Test
    fun `una posa lunga non fa aspettare piu del tetto`() {
        assertEquals(2_000L, exposureGuardMillis(auto(4.0)))
    }

    /** In pieno giorno la posa è millesimi: l'attesa scende al minimo e il gimbal riparte subito. */
    @Test
    fun `con una posa breve si aspetta il minimo`() {
        assertEquals(300L, exposureGuardMillis(auto(1.0 / 500.0)))
    }

    @Test
    fun `una posa media sta fra il minimo e il tetto`() {
        val guard = exposureGuardMillis(auto(0.25))
        assertTrue("Guardia di $guard ms", guard in 301L..1_999L)
        // Il doppio della posa più il margine fisso.
        assertEquals(750L, guard)
    }

    @Test
    fun `l'attesa cresce con la posa`() {
        val breve = exposureGuardMillis(auto(0.05))
        val lunga = exposureGuardMillis(auto(0.5))
        assertTrue("$breve dovrebbe essere minore di $lunga", breve < lunga)
    }

    @Test
    fun `la posa si legge come frazione di secondo`() {
        assertTrue(describeExposure(auto(1.0 / 250.0)).contains("1/250 s"))
        assertTrue(describeExposure(auto(2.0)).contains("2,0 s") || describeExposure(auto(2.0)).contains("2.0 s"))
        assertTrue(describeExposure(auto(0.01)).contains("ISO 400"))
    }

    @Test
    fun `senza esposizione il log lo dice invece di inventare`() {
        assertEquals("non dichiarata dalla camera", describeExposure(null))
    }

    @Test
    fun `il programma manuale si riconosce`() {
        val manual = ExposureReading(
            program = LunaProtocolCodes.ExposureProgram.MANUAL,
            iso = 100,
            shutterSeconds = 0.02,
        )
        assertTrue(manual.isManual)
        assertTrue(describeExposure(manual).startsWith("manuale"))
    }
}
