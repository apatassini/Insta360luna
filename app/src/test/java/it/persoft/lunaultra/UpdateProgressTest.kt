package it.persoft.lunaultra

import it.persoft.lunaultra.ui.UpdateUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Una percentuale su un totale ignoto sarebbe inventata, e una barra che si muove a caso è
 * peggio di nessuna barra: quando il server non dichiara la dimensione si mostrano i megabyte.
 */
class UpdateProgressTest {

    @Test
    fun `con la dimensione dichiarata c'e' la percentuale`() {
        val state = UpdateUiState.Downloading("main", downloaded = 512L, total = 2_048L)
        assertEquals(0.25f, state.fraction!!, 0.001f)
        assertEquals(25, state.percent)
    }

    @Test
    fun `senza dimensione dichiarata non c'e' percentuale`() {
        val state = UpdateUiState.Downloading("main", downloaded = 512L, total = -1L)
        assertNull(state.fraction)
        assertNull(state.percent)
    }

    @Test
    fun `l'avanzamento non supera il totale neanche se i byte lo superano`() {
        val state = UpdateUiState.Downloading("main", downloaded = 5_000L, total = 2_048L)
        assertEquals(1f, state.fraction!!, 0.001f)
        assertEquals(100, state.percent)
    }
}
