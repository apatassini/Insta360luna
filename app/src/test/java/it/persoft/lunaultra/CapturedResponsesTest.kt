package it.persoft.lunaultra

import it.persoft.lunaultra.protocol.Hex
import it.persoft.lunaultra.protocol.LunaMessages
import it.persoft.lunaultra.protocol.LunaProtocolCodes.BatteryField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.CaptureStatusField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.OptionType
import it.persoft.lunaultra.protocol.LunaProtocolCodes.OptionsField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.StorageField
import it.persoft.lunaultra.protocol.ProtoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Risposte reali catturate da una **Insta360 Luna Ultra, firmware v1.0.288**.
 *
 * Sono i byte esatti letti dal log dell'app sul campo, non payload costruiti a tavolino: se un
 * numero di campo cambia, questi test cadono prima che l'app mostri una batteria sbagliata o
 * uno spazio disco assurdo.
 */
class CapturedResponsesTest {

    private val optionsField = LunaMessages.FIELD_OPTIONS_VALUE

    private fun bytes(hex: String) = Hex.decodeOrNull(hex)!!

    /** Risposta a GET_OPTIONS con [BATTERY_STATUS, STORAGE_STATE]. */
    private val batteryAndStorage = bytes(
        "080B0814121D5A06080010562000A201120800108080A88B" +
            "A405188080A084B9072003"
    )

    /** Risposta a GET_OPTIONS con [CAMERA_TYPE, SERIAL_NUMBER, FIRMWAREREVISION]. */
    private val deviceInfo = bytes(
        "0830080F081E12317A0E42544C41334142444E5048574E56" +
            "F2010876312E302E323838820313496E737461333630204C" +
            "756E6120556C747261"
    )

    /** Risposta a GET_CURRENT_CAPTURE_STATUS con la camera ferma. */
    private val captureIdle = bytes("0A06080010005000")

    @Test
    fun `la batteria si legge dal campo 11 delle opzioni`() {
        val reader = ProtoReader(batteryAndStorage)
        val level = reader.intOrNull(optionsField, OptionsField.BATTERY_STATUS, BatteryField.BATTERY_LEVEL)

        assertEquals(86, level)
    }

    /**
     * Lo spazio della scheda non entra in un Int: 181 GB liberi troncati a 32 bit darebbero un
     * numero negativo, ed è esattamente l'errore che questo test impedisce.
     */
    @Test
    fun `lo spazio disco si legge a 64 bit`() {
        val reader = ProtoReader(batteryAndStorage)
        val free = reader.longOrNull(optionsField, OptionsField.STORAGE_STATE, StorageField.FREE_SPACE)
        val total = reader.longOrNull(optionsField, OptionsField.STORAGE_STATE, StorageField.TOTAL_SPACE)

        assertEquals(181_486_092_288L, free)
        assertEquals(255_827_902_464L, total)
        assertEquals(true, free!! < total!!)
    }

    @Test
    fun `modello seriale e firmware si leggono dai campi 48 15 e 30`() {
        val reader = ProtoReader(deviceInfo)

        assertEquals("Insta360 Luna Ultra", reader.stringOrNull(optionsField, OptionsField.CAMERA_TYPE))
        assertEquals("BTLA3ABDNPHWNV", reader.stringOrNull(optionsField, OptionsField.SERIAL_NUMBER))
        assertEquals("v1.0.288", reader.stringOrNull(optionsField, OptionsField.FIRMWARE_REVISION))
    }

    /** Con la camera ferma lo stato è NOT_CAPTURE, annidato nel campo 1 della risposta. */
    @Test
    fun `lo stato di cattura a riposo e zero`() {
        val reader = ProtoReader(captureIdle)
        val state = reader.intOrNull(1, CaptureStatusField.STATE)

        assertNotNull(state)
        assertEquals(0, state)
    }

    /** La richiesta che ha prodotto quella risposta, ricomposta byte per byte. */
    @Test
    fun `la richiesta di batteria e storage e quella osservata`() {
        val body = LunaMessages.getOptions(OptionType.BATTERY_STATUS, OptionType.STORAGE_STATE)
        assertEquals("080b0814", Hex.encode(body, separator = "").lowercase())
    }
}
