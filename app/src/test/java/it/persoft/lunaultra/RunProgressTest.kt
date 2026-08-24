package it.persoft.lunaultra

import it.persoft.lunaultra.timelapse.RunPhase
import it.persoft.lunaultra.timelapse.RunState
import it.persoft.lunaultra.timelapse.ShootingMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * L'avanzamento di una sequenza fotografica conta i passi fatti, non le foto portate a casa.
 *
 * La differenza si vede solo quando qualcosa va storto, ed è proprio lì che serve: con la barra
 * agganciata agli scatti riusciti, una camera che ne perde tre resta ferma al 87% mentre il
 * gimbal continua a girare, e chi guarda pensa che si sia bloccata.
 */
class RunProgressTest {

    private fun photoRun(taken: Int, missed: Int, planned: Int) = RunState(
        phase = RunPhase.RUNNING,
        mode = ShootingMode.FOTO,
        shotsTaken = taken,
        shotsMissed = missed,
        shotsPlanned = planned,
    )

    @Test
    fun `senza scatti persi l'avanzamento e' la frazione degli scatti fatti`() {
        assertEquals(0.5f, photoRun(taken = 10, missed = 0, planned = 20).overallProgress, 1e-4f)
    }

    @Test
    fun `uno scatto perso avanza comunque la barra`() {
        assertEquals(0.5f, photoRun(taken = 8, missed = 2, planned = 20).overallProgress, 1e-4f)
    }

    @Test
    fun `l'ultimo passo chiude la barra anche se la camera ha perso qualcosa`() {
        assertEquals(1f, photoRun(taken = 17, missed = 3, planned = 20).overallProgress, 1e-4f)
    }

    @Test
    fun `l'avanzamento non supera il pieno`() {
        assertEquals(1f, photoRun(taken = 22, missed = 1, planned = 20).overallProgress, 1e-4f)
    }

    /** Nelle modalità continue il riferimento resta il tempo: gli scatti lì non esistono. */
    @Test
    fun `in video l'avanzamento resta quello del tempo`() {
        val state = RunState(
            phase = RunPhase.RUNNING,
            mode = ShootingMode.VIDEO,
            elapsedSeconds = 15f,
            totalSeconds = 60f,
        )
        assertEquals(0.25f, state.overallProgress, 1e-4f)
    }
}
