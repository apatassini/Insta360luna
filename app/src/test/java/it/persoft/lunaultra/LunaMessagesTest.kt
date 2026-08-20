package it.persoft.lunaultra

import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaMessages
import it.persoft.lunaultra.protocol.LunaProtocolCodes.OptionType
import it.persoft.lunaultra.protocol.LunaProtocolCodes.TimelapseMode
import it.persoft.lunaultra.protocol.ProtoReader
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * I corpi dei comandi vanno confrontati byte per byte con quelli osservati: un numero di campo
 * sbagliato non produce un errore, produce una camera che ignora il comando in silenzio.
 */
class LunaMessagesTest {

    /**
     * La richiesta di apertura sessione dell'app ufficiale chiede CAMERA_TYPE (48),
     * SERIAL_NUMBER (15) e BATTERY_STATUS (11): tre varint ripetuti nel campo 1.
     */
    @Test
    fun `la richiesta di opzioni corrisponde a quella catturata`() {
        val body = LunaMessages.getOptions(
            OptionType.CAMERA_TYPE,
            OptionType.SERIAL_NUMBER,
            OptionType.BATTERY_STATUS,
        )
        assertEquals("0830080f080b", Hex.encode(body).replace(" ", "").lowercase())
    }

    @Test
    fun `stop capture mette la modalita nel campo 2 non nel campo 1`() {
        val start = ProtoReader(LunaMessages.startCapture(1)).fields()
        val stop = ProtoReader(LunaMessages.stopCapture(1)).fields()

        assertEquals(1, start.single().number)
        assertEquals(2, stop.single().number)
    }

    @Test
    fun `le opzioni timelapse annidano durata e intervallo nel campo 1`() {
        val body = LunaMessages.setTimelapseOptions(
            durationSeconds = 60,
            intervalSeconds = 2,
            mode = TimelapseMode.STATIC_TIMELAPSE_VIDEO,
        )
        val reader = ProtoReader(body)

        assertEquals(60, reader.intOrNull(1, 1))
        assertEquals(2, reader.intOrNull(1, 2))
        assertEquals(TimelapseMode.STATIC_TIMELAPSE_VIDEO, reader.intOrNull(2))
    }

    /** Un intervallo a zero non ha senso e la camera lo rifiuterebbe: viene alzato a 1. */
    @Test
    fun `un intervallo nullo viene portato al minimo`() {
        val body = LunaMessages.setTimelapseOptions(durationSeconds = 0, intervalSeconds = 0, mode = 0)
        assertEquals(1, ProtoReader(body).intOrNull(1, 2))
    }

    @Test
    fun `le velocita del gimbal usano lo zig-zag per i valori negativi`() {
        val body = LunaMessages.gimbalVelocity(panField = 1, panValue = -40, tiltField = 2, tiltValue = 40)
        val fields = ProtoReader(body).fields()

        assertEquals(-40, (fields[0] as it.persoft.lunaultra.protocol.ProtoField.VarInt).asSInt)
        assertEquals(40, (fields[1] as it.persoft.lunaultra.protocol.ProtoField.VarInt).asSInt)
    }
}
