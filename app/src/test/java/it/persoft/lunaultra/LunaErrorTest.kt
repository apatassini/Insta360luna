package it.persoft.lunaultra

import it.persoft.lunaultra.camera.LunaError
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Il riconoscimento dell'errore è l'unica cosa che separa "comando inesistente" da "comando
 * che esiste": se sbagliasse, lo scanner darebbe risposte inventate.
 */
class LunaErrorTest {

    @Test
    fun `riconosce un errore con codice e messaggio`() {
        val payload = ProtoWriter()
            .int32(1, LunaProtocolCodes.ErrorCode.UNKNOWN_MSG_PAYLOAD)
            .string(2, "msg execute err.")
            .toByteArray()

        val error = LunaError.parse(payload)!!
        assertEquals(LunaProtocolCodes.ErrorCode.UNKNOWN_MSG_PAYLOAD, error.code)
        assertEquals("msg execute err.", error.message)
        assertTrue(error.isBadPayload)
    }

    @Test
    fun `riconosce un errore senza messaggio`() {
        val payload = ProtoWriter().int32(1, LunaProtocolCodes.ErrorCode.UNKNOWN_MSG_CODE).toByteArray()

        val error = LunaError.parse(payload)!!
        assertTrue(error.isUnknownCommand)
        assertNull(error.message)
    }

    @Test
    fun `un payload vuoto non e un errore`() {
        assertNull(LunaError.parse(ByteArray(0)))
    }

    /**
     * Un `GetOptionsResp` inizia con l'elenco degli option_type richiesti nel campo 1: senza
     * il limite sul valore, una richiesta di CAMERA_TYPE (48) verrebbe letta come un errore.
     */
    @Test
    fun `una risposta di opzioni non viene scambiata per un errore`() {
        val payload = ProtoWriter()
            .int32(1, LunaProtocolCodes.OptionType.CAMERA_TYPE)
            .message(2) { string(LunaProtocolCodes.OptionsField.CAMERA_TYPE, "Luna Ultra") }
            .toByteArray()

        assertNull(LunaError.parse(payload))
    }

    @Test
    fun `un messaggio con troppi campi non e un errore`() {
        val payload = ProtoWriter().int32(1, 1).string(2, "x").int32(3, 9).toByteArray()
        assertNull(LunaError.parse(payload))
    }
}
