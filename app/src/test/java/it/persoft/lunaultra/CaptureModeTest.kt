package it.persoft.lunaultra

import it.persoft.lunaultra.camera.CameraMode
import it.persoft.lunaultra.protocol.LunaMessages
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le modalità di ripresa.
 *
 * Il difetto che questi test bloccano è stato misurato sulla camera: premendo «foto» partiva una
 * panoramica. Due cause distinte — il modo dello scatto preso dall'enum sbagliato, e la
 * sotto-modalità della camera mai impostata — ed entrambe silenziose, perché la camera esegue
 * quel che le arriva senza lamentarsi.
 */
class CaptureModeTest {

    /**
     * `TakePicture.Mode.NORMAL` vale 0. Il valore 1 appartiene a `CaptureMode` (dove è il
     * normale) ma in `TakePicture` è l'AEB: passarlo chiede un bracketing di esposizione.
     */
    @Test
    fun `lo scatto normale usa il modo zero e non quello di CaptureMode`() {
        val body = LunaMessages.takePicture(LunaProtocolCodes.TakePictureMode.NORMAL)
        assertEquals(0, ProtoReader(body).intOrNull(1))
        assertEquals(1, LunaProtocolCodes.CaptureMode.NORMAL)
        assertEquals(0, LunaProtocolCodes.TakePictureMode.NORMAL)
    }

    /** Il campo della panoramica si manda solo quando la si vuole davvero. */
    @Test
    fun `la panoramica per singolo scatto viaggia sul campo cinque`() {
        val plain = LunaMessages.takePicture(LunaProtocolCodes.TakePictureMode.NORMAL)
        val pano = LunaMessages.takePicture(LunaProtocolCodes.TakePictureMode.NORMAL, instaPano = true)

        assertNull(ProtoReader(plain).intOrNull(5))
        assertEquals(1, ProtoReader(pano).intOrNull(5))
    }

    /** `SetOptions { option_types = 1; Options value = 2 }`: il valore va annidato. */
    @Test
    fun `la modalita foto si invia come sotto-modalita annidata`() {
        val body = LunaMessages.setOption(
            optionType = LunaProtocolCodes.OptionType.PHOTO_SUB_MODE,
            field = LunaProtocolCodes.OptionsField.PHOTO_SUB_MODE,
            value = LunaProtocolCodes.PhotoSubMode.SINGLE,
        )
        val reader = ProtoReader(body)

        assertEquals(LunaProtocolCodes.OptionType.PHOTO_SUB_MODE, reader.intOrNull(1))
        assertEquals(LunaProtocolCodes.PhotoSubMode.SINGLE, reader.intOrNull(2, 40))
    }

    /** Le opzioni fotografiche vanno scritte dentro il function mode a cui appartengono. */
    @Test
    fun `la proporzione della panoramica porta con se il function mode`() {
        val body = LunaMessages.setPhotographyOption(
            optionType = LunaProtocolCodes.PhotographyOptionType.PANO_ASPECT,
            field = LunaProtocolCodes.PhotographyOptionsField.PANO_ASPECT,
            value = LunaProtocolCodes.PanoAspect.RATIO_2_1,
            functionMode = LunaProtocolCodes.FunctionMode.NORMAL_POWER_PANO_IMAGE,
        )
        val reader = ProtoReader(body)

        assertEquals(98, reader.intOrNull(1))
        assertEquals(LunaProtocolCodes.PanoAspect.RATIO_2_1, reader.intOrNull(2, 98))
        assertEquals(LunaProtocolCodes.FunctionMode.NORMAL_POWER_PANO_IMAGE, reader.intOrNull(3))
    }

    /**
     * La camera lascia l'altra sotto-modalità al suo valore sentinella invece di azzerarla:
     * leggendo alla lettera, una camera in video sembrerebbe anche in foto.
     */
    @Test
    fun `il video vince sulla sotto-modalita foto rimasta indietro`() {
        val mode = CameraMode.fromSubModes(
            photoSubMode = LunaProtocolCodes.PhotoSubMode.SINGLE,
            videoSubMode = LunaProtocolCodes.VideoSubMode.TIMELAPSE,
        )
        assertEquals(CameraMode.TIMELAPSE, mode)
    }

    @Test
    fun `la panoramica si riconosce dalla sotto-modalita otto`() {
        val mode = CameraMode.fromSubModes(
            photoSubMode = LunaProtocolCodes.PhotoSubMode.INSTA_PANO,
            videoSubMode = LunaProtocolCodes.VideoSubMode.NONE,
        )
        assertEquals(CameraMode.PANORAMA, mode)
        assertTrue(CameraMode.PANORAMA.isPhoto)
        assertTrue(CameraMode.PANORAMA.hasPanoAspect)
    }

    @Test
    fun `senza sotto-modalita utili la modalita resta ignota`() {
        assertNull(
            CameraMode.fromSubModes(
                photoSubMode = LunaProtocolCodes.PhotoSubMode.NONE,
                videoSubMode = LunaProtocolCodes.VideoSubMode.NONE,
            )
        )
    }
}
