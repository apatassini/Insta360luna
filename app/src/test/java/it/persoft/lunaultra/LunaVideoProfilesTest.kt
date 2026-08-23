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

    @Test
    fun `il 2_7K usa i codici misurati sulla Luna e comprende 48 fps`() {
        val profiles = LunaVideoProfiles.all.filter { it.resolution == "2.7K" }
        assertEquals(setOf(242, 243, 244, 245, 331, 246, 247, 248), profiles.map { it.code }.toSet())
        assertEquals(setOf(120, 100, 60, 50, 48, 30, 25, 24), profiles.map { it.fps }.toSet())
    }

    @Test
    fun `slow motion espone solo i frame rate elevati`() {
        val profiles = LunaVideoProfiles.forMode(CameraMode.SLOW_MOTION)
        assertTrue(profiles.isNotEmpty())
        assertTrue(profiles.all { it.fps in setOf(240, 200, 120, 100) })
    }
}
