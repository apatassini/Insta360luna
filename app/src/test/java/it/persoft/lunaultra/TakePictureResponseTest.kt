package it.persoft.lunaultra

import it.persoft.lunaultra.camera.photoUriFromResponse
import it.persoft.lunaultra.camera.takePictureStateFrom
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoWriter
import it.persoft.lunaultra.protocol.Ucd2
import it.persoft.lunaultra.protocol.Ucd2Frame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Quello che la camera racconta di uno scatto.
 *
 * Sono due informazioni che c'erano già e non venivano lette, e la seconda spiega la prima: la
 * camera dice quando sta comprimendo e quando sta scrivendo sulla scheda (notifica 8202), e a
 * file scritto risponde al comando con il percorso del file. Senza il percorso, unire una
 * panoramica voleva dire confrontare l'elenco dei file prima e dopo e sperare che il conto
 * tornasse — e a uno scatto perso non tornava, con tutte le foto accoppiate all'angolo sbagliato.
 */
class TakePictureResponseTest {

    private fun response(uri: String): ByteArray = ProtoWriter()
        .message(LunaProtocolCodes.PhotoField.IMAGE) { string(LunaProtocolCodes.PhotoField.URI, uri) }
        .toByteArray()

    private fun notification(state: Int?): Ucd2Frame = Ucd2Frame(
        type = Ucd2.TYPE_FILE,
        code = LunaProtocolCodes.NOTIFICATION_TAKE_PICTURE_STATE,
        payload = state?.let { ProtoWriter().int32(1, it).toByteArray() } ?: ByteArray(0),
    )

    @Test
    fun `la risposta allo scatto porta il percorso del file`() {
        val uri = "/DCIM/Camera01/IMG_20260824_143012_00_001.jpg"
        assertEquals(uri, photoUriFromResponse(response(uri)))
    }

    @Test
    fun `una risposta vuota non inventa un percorso`() {
        assertNull(photoUriFromResponse(ByteArray(0)))
    }

    /** Un percorso di soli spazi non è un percorso: chi lo riceve deve poter passare al ripiego. */
    @Test
    fun `un percorso vuoto vale come assente`() {
        assertNull(photoUriFromResponse(response("   ")))
    }

    @Test
    fun `la notifica dice che la camera sta scrivendo sulla scheda`() {
        assertEquals(
            LunaProtocolCodes.TakePictureState.WRITE_FILE,
            takePictureStateFrom(notification(LunaProtocolCodes.TakePictureState.WRITE_FILE)),
        )
    }

    /**
     * In proto3 uno zero non viaggia: il corpo vuoto su questo codice è l'otturatore, non un
     * valore mancante.
     */
    @Test
    fun `la notifica senza campo e' l'otturatore`() {
        assertEquals(LunaProtocolCodes.TakePictureState.SHUTTER, takePictureStateFrom(notification(null)))
    }

    @Test
    fun `le altre notifiche non vengono lette come stato dello scatto`() {
        val other = Ucd2Frame(
            type = Ucd2.TYPE_FILE,
            code = LunaProtocolCodes.NOTIFICATION_BATTERY_UPDATE,
            payload = ProtoWriter().int32(1, 2).toByteArray(),
        )
        assertNull(takePictureStateFrom(other))
    }

    @Test
    fun `i tre tempi dello scatto hanno un nome leggibile`() {
        assertEquals("scrittura sulla scheda", LunaProtocolCodes.TakePictureState.name(2))
        assertEquals("compressione", LunaProtocolCodes.TakePictureState.name(1))
        assertEquals("otturatore", LunaProtocolCodes.TakePictureState.name(0))
    }
}
