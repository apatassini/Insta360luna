package it.persoft.lunaultra

import it.persoft.lunaultra.gimbal.GimbalLimitSignal
import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.Ucd2
import it.persoft.lunaultra.protocol.Ucd2Frame
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GimbalLimitSignalTest {
    @Test
    fun `decodifica i limiti verticale e orizzontale dalla notifica 8302`() {
        val pitch = signal("08 00 10 01 18 00 20 00 28 00 30 00 38 00 40 00 48 00")
        assertTrue(pitch.pitchLimit)
        assertFalse(pitch.yawLimit)

        val yaw = signal("08 00 10 00 18 01 20 00 28 00 30 00 38 00 40 00 48 00")
        assertFalse(yaw.pitchLimit)
        assertTrue(yaw.yawLimit)
    }

    private fun signal(hex: String) = GimbalLimitSignal.from(
        Ucd2Frame(
            type = Ucd2.TYPE_FILE,
            code = LunaProtocolCodes.NOTIFICATION_PTZ_STATE_OBSERVED,
            payload = Hex.decodeOrNull(hex)!!,
        ),
    )!!
}
