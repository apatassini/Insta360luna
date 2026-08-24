package it.persoft.lunaultra

import it.persoft.lunaultra.protocol.LunaProtocolCodes
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lo stato di cattura che non si chiude.
 *
 * Misurato sulla camera: con il flusso dell'anteprima fermo, il primo scatto la porta in
 * `SINGLE_SHOOTING` e lì resta. Tre minuti e mezzo, sedici scatti comandati, zero file salvati,
 * e lo stato è tornato a riposo solo quando il flusso è ripartito. Questi test bloccano la
 * lettura dello stato — che è come l'app se ne accorge — perché sbagliarla significa credere
 * che una camera appesa stia lavorando.
 */
class StuckCaptureTest {

    @Test
    fun `lo scatto singolo conta come occupata`() {
        assertTrue(LunaProtocolCodes.CaptureState.isBusy(LunaProtocolCodes.CaptureState.SINGLE_SHOOTING))
    }

    @Test
    fun `a riposo la camera non e' occupata`() {
        assertFalse(LunaProtocolCodes.CaptureState.isBusy(LunaProtocolCodes.CaptureState.NOT_CAPTURE))
    }

    /**
     * Il nome finisce nel log ed è quello che si legge per capire dove si è piantata: se
     * diventasse un numero crudo, il log direbbe «stato 4» e non «scatto singolo».
     */
    @Test
    fun `lo stato ha un nome leggibile nel log`() {
        val name = LunaProtocolCodes.CaptureState.name(LunaProtocolCodes.CaptureState.SINGLE_SHOOTING)
        assertFalse("Lo stato 4 dovrebbe avere un nome, invece è «$name»", name == "#4")
    }
}
