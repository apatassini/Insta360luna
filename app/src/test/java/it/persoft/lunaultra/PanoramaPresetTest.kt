package it.persoft.lunaultra

import it.persoft.lunaultra.timelapse.PanoramaPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * I preset devono restare riconoscibili dopo essere stati applicati, o il pannello mostrerebbe
 * "personalizzata" un istante dopo che si è scelta una voce dall'elenco.
 */
class PanoramaPresetTest {

    @Test
    fun `ogni preset si riconosce dai propri gradi`() {
        PanoramaPreset.entries.forEach { preset ->
            assertEquals(
                preset,
                PanoramaPreset.matching(preset.horizontalDegrees, preset.verticalDegrees),
            )
        }
    }

    @Test
    fun `i due sedici noni sono l'uno il ribaltamento dell'altro`() {
        val wide = PanoramaPreset.WIDE_16_9
        val tall = PanoramaPreset.TALL_16_9
        assertEquals(wide.horizontalDegrees, tall.verticalDegrees, 0.01f)
        assertEquals(wide.verticalDegrees, tall.horizontalDegrees, 0.01f)
        assertEquals(16f / 9f, wide.horizontalDegrees / wide.verticalDegrees, 0.01f)
    }

    @Test
    fun `una copertura scritta a mano non corrisponde a nessun preset`() {
        assertNull(PanoramaPreset.matching(137f, 42f))
    }

    /** Nessuna voce può chiedere più corsa di quella che la camera ha davvero. */
    @Test
    fun `ogni preset sta dentro la corsa della camera`() {
        PanoramaPreset.entries.forEach { preset ->
            assertTrue(preset.label, preset.horizontalDegrees <= 292f)
            assertTrue(preset.label, preset.verticalDegrees <= 177f)
        }
    }
}
