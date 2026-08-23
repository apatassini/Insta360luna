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

    /**
     * Lo zero hardware è `08 02`: un solo campo, nessun asse. È il payload osservato in
     * cattura, e resta il metro di paragone per le altre azioni che si provano a mano.
     */
    @Test
    fun `lo zero hardware e l'azione 2 senza assi`() {
        assertEquals("0802", Hex.encode(LunaMessages.gimbalBackCenter()).replace(" ", "").lowercase())
        assertEquals(
            Hex.encode(LunaMessages.gimbalBackCenter()),
            Hex.encode(LunaMessages.gimbalAction(LunaMessages.GimbalAction.BACK_CENTER)),
        )
    }

    /**
     * I tre valori misurati sulla camera. Il selfie è un interruttore: se un giorno il numero
     * cambia, questo test è il posto in cui la modifica va dichiarata invece che subita.
     */
    @Test
    fun `le tre azioni note del gimbal hanno i numeri misurati sulla camera`() {
        assertEquals(1, LunaMessages.GimbalAction.MOVE)
        assertEquals(2, LunaMessages.GimbalAction.BACK_CENTER)
        assertEquals(3, LunaMessages.GimbalAction.SELFIE_TOGGLE)
        assertEquals("0803", Hex.encode(LunaMessages.gimbalAction(3)).replace(" ", "").lowercase())
    }

    @Test
    fun `un'azione arbitraria porta solo il campo 1`() {
        val fields = ProtoReader(LunaMessages.gimbalAction(7)).fields()
        assertEquals(1, fields.size)
        assertEquals(1, fields.single().number)
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
    fun `i vettori gimbal riproducono byte per byte le catture di Insta360Linker`() {
        fun assertBody(expected: String, horizontal: Int, vertical: Int) =
            assertEquals(expected, Hex.encode(LunaMessages.gimbalMove(horizontal, vertical), separator = "").lowercase())

        assertBody("08011200", horizontal = 0, vertical = 0)
        assertBody("080112020848", horizontal = 0, vertical = 36)
        assertBody("080112020876", horizontal = 0, vertical = 59)
        assertBody("0801120508b4011014", horizontal = 10, vertical = 90)
        assertBody("0801120508c401101a", horizontal = 13, vertical = 98)
        assertBody("08011205082910c001", horizontal = 96, vertical = -21)
    }

    @Test
    fun `il ricentraggio gimbal usa la vera azione hardware`() {
        assertEquals("0802", Hex.encode(LunaMessages.gimbalBackCenter(), separator = "").lowercase())
    }

    @Test
    fun `la velocita hardware gimbal usa i pacchetti catturati`() {
        val set = Hex.encode(LunaMessages.setGimbalSpeed(2), separator = "").lowercase()
        val refresh = Hex.encode(LunaMessages.refreshGimbalSpeed(), separator = "").lowercase()

        assertEquals("08551205aa050210021806", set)
        assertEquals("08631006", refresh)
    }

    @Test
    fun `il formato video usa il campo record resolution e il function mode`() {
        val body = LunaMessages.setVideoProfile(profileCode = 154, functionMode = 7)
        val reader = ProtoReader(body)

        assertEquals(31, reader.intOrNull(1))
        assertEquals(154, reader.intOrNull(2, 31))
        assertEquals(7, reader.intOrNull(3))
    }
}
