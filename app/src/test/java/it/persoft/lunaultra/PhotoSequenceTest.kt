package it.persoft.lunaultra

import it.persoft.lunaultra.timelapse.ShootingMode
import it.persoft.lunaultra.timelapse.TimelapseSequence
import it.persoft.lunaultra.timelapse.Waypoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il conteggio degli scatti di una panoramica non è una comodità: se sbagliasse, la sequenza
 * fotograferebbe due volte lo stesso punto alle giunzioni fra i tratti, che è proprio dove
 * l'unione in post produzione si rompe.
 */
class PhotoSequenceTest {

    private fun sequenceOf(points: Int, shotsPerLeg: Int) = TimelapseSequence(
        waypoints = List(points) { Waypoint(name = ('A' + it).toString(), pan = it * 30f, tilt = 0f) },
        mode = ShootingMode.FOTO,
        shotsPerLeg = shotsPerLeg,
    )

    @Test
    fun `il punto in comune fra due tratti si scatta una volta sola`() {
        // 2 tratti da 5 scatti: 5 + 5 = 10 in teoria, ma B è in comune → 9.
        assertEquals(9, sequenceOf(points = 3, shotsPerLeg = 5).totalShots())
    }

    @Test
    fun `un solo tratto scatta esattamente gli scatti richiesti`() {
        assertEquals(6, sequenceOf(points = 2, shotsPerLeg = 6).totalShots())
    }

    @Test
    fun `senza punti a sufficienza non ci sono scatti`() {
        assertEquals(0, sequenceOf(points = 1, shotsPerLeg = 5).totalShots())
    }

    /** Con meno di due scatti il tratto non ha né partenza né arrivo: il minimo è due. */
    @Test
    fun `gli scatti per tratto non scendono sotto due`() {
        val sequence = sequenceOf(points = 2, shotsPerLeg = 1)
        assertEquals(2, sequence.effectiveShotsPerLeg())
        assertEquals(2, sequence.totalShots())
    }

    @Test
    fun `la durata stimata include attesa e scatto, non solo il movimento`() {
        val sequence = sequenceOf(points = 2, shotsPerLeg = 4).copy(
            useTotalDuration = true,
            totalDurationSeconds = 40f,
            settleSeconds = 2f,
        )
        val movement = sequence.effectiveTotalSeconds()
        val estimated = sequence.estimatedPhotoSeconds()

        assertTrue("la stima deve superare il solo movimento", estimated > movement)
        val overhead = sequence.totalShots() * (2f + TimelapseSequence.ESTIMATED_SHOT_SECONDS)
        assertEquals(movement + overhead, estimated, 0.01f)
    }

    @Test
    fun `la modalita foto non e continua, le altre si`() {
        assertTrue(ShootingMode.VIDEO.movesContinuously)
        assertTrue(ShootingMode.TIMELAPSE_CAMERA.movesContinuously)
        assertTrue(!ShootingMode.FOTO.movesContinuously)
    }
}
