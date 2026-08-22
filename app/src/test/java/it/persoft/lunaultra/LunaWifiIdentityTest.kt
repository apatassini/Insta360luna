package it.persoft.lunaultra

import it.persoft.lunaultra.net.LunaWifiIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LunaWifiIdentityTest {

    @Test
    fun `normalizza ssid quotato e riconosce la Luna`() {
        assertEquals(
            "Luna Ultra NPHWNV.OSC",
            LunaWifiIdentity.normalizeSsid("\"Luna Ultra NPHWNV.OSC\""),
        )
        assertTrue(LunaWifiIdentity.isLunaSsid("Luna Ultra NPHWNV.OSC"))
        assertTrue(LunaWifiIdentity.isLunaSsid("luna ultra test"))
        assertFalse(LunaWifiIdentity.isLunaSsid("Rairalagon"))
    }

    @Test
    fun `scarta le varianti di ssid sconosciuto usate da Android`() {
        assertNull(LunaWifiIdentity.normalizeSsid("<unknown ssid>"))
        assertNull(LunaWifiIdentity.normalizeSsid("\"<unknown ssid>\""))
        assertNull(LunaWifiIdentity.normalizeSsid("unknown ssid"))
    }

    @Test
    fun `riconosce indirizzo locale e gateway della camera`() {
        assertTrue(LunaWifiIdentity.isCameraSubnetAddress("192.168.42.23"))
        assertFalse(LunaWifiIdentity.isCameraSubnetAddress("10.215.173.1"))
        assertTrue(LunaWifiIdentity.isCameraGateway("192.168.42.1", "192.168.42.1"))
        assertFalse(LunaWifiIdentity.isCameraGateway("192.168.1.1", "192.168.42.1"))
    }
}
