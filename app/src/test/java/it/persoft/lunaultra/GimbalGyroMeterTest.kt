package it.persoft.lunaultra

import it.persoft.lunaultra.gimbal.MisuraCurvaGiroscopio
import org.junit.Assert.assertEquals
import org.junit.Test

/** Numeri delle tre registrazioni fatte sulla Luna reale il 29 agosto 2026. */
class GimbalGyroMeterTest {

    @Test
    fun `le prove reali 20 50 80 producono la curva misurata`() {
        val venti = MisuraCurvaGiroscopio(12.80f, 12.92f, 1f)
        val cinquanta = MisuraCurvaGiroscopio(32.35f, 31.93f, 1f)
        val ottanta = MisuraCurvaGiroscopio(51.31f, 51.29f, 1f)

        assertEquals(12.86f, venti.gradiSecondo, 0.01f)
        assertEquals(32.14f, cinquanta.gradiSecondo, 0.01f)
        assertEquals(51.30f, ottanta.gradiSecondo, 0.01f)
        assertEquals(0.12f, venti.scartoChiusuraGradi, 0.01f)
        assertEquals(0.42f, cinquanta.scartoChiusuraGradi, 0.01f)
        assertEquals(0.02f, ottanta.scartoChiusuraGradi, 0.01f)
    }
}
