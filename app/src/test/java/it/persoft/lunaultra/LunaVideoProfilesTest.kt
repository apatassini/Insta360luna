package it.persoft.lunaultra

import it.persoft.lunaultra.camera.CameraMode
import it.persoft.lunaultra.data.LunaVideoProfiles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaVideoProfilesTest {
    @Test
    fun `il profilo 8K30 ha il codice osservato nel protocollo`() {
        val profile = LunaVideoProfiles.all.single { it.code == 154 }
        assertEquals("8K", profile.resolution)
        assertEquals(30, profile.fps)
        assertEquals("16:9", profile.aspect)
    }

    @Test
    fun `il timelapse propone solo i tre formati a trenta fps`() {
        val profiles = LunaVideoProfiles.forMode(CameraMode.TIMELAPSE)
        assertEquals(setOf("4K", "2.7K", "1080p"), profiles.map { it.resolution }.toSet())
        assertTrue(profiles.all { it.fps == 30 && it.aspect == "16:9" })
    }
}
