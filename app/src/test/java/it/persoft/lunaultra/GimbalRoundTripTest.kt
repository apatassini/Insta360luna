package it.persoft.lunaultra

import it.persoft.lunaultra.gimbal.GimbalCalibrator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il verdetto di una tappa deve parlare di gradi di errore, non di punteggi.
 *
 * Il numero utile è "di quanto ha sbagliato tornando al punto in cui era già stato": è un
 * errore vero, confrontabile con la tolleranza di un waypoint. Una percentuale di somiglianza
 * fra due inquadrature diverse non lo è.
 */
class GimbalRoundTripTest {

    private fun result(home: Float?, stop: Float?, comparable: Boolean = true) =
        GimbalCalibrator.RoundTripResult(
            label = "destra",
            axis = "pan",
            degrees = 45f,
            homeErrorDeg = home,
            stopErrorDeg = stop,
            homeComparable = comparable && home != null,
            stopComparable = comparable && stop != null,
        )

    @Test
    fun `lo scarto peggiore e' quello che conta`() {
        assertEquals(1.8f, result(0.4f, 1.8f).worstErrorDeg!!, 0.001f)
        assertEquals(2.5f, result(2.5f, 0.1f).worstErrorDeg!!, 0.001f)
    }

    @Test
    fun `un ritorno dentro tolleranza si chiama esatto`() {
        assertTrue(result(0.5f, 0.9f).verdict().contains("RITORNO ESATTO"))
    }

    @Test
    fun `oltre tolleranza il verdetto porta i gradi, non un voto`() {
        val verdict = result(0.5f, 7.4f).verdict()
        assertTrue(verdict, verdict.contains("7,4°") || verdict.contains("7.4°"))
    }

    @Test
    fun `senza immagini confrontabili non si inventa un esito`() {
        assertTrue(result(null, null, comparable = false).verdict().contains("NON CONFRONTABILE"))
    }
}
