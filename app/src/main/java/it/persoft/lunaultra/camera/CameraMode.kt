package it.persoft.lunaultra.camera

import it.persoft.lunaultra.protocol.LunaProtocolCodes

/**
 * Le modalità di ripresa della camera.
 *
 * Una modalità è tre fatti legati insieme: l'opzione di sotto-modalità che la seleziona, il
 * `FunctionMode` sotto cui la camera memorizza le sue impostazioni fotografiche, e — per il
 * video — il `CaptureMode` che `START_CAPTURE` si aspetta. Stanno in un posto solo perché usano
 * tre enum diversi con nomi simili, ed è facilissimo scambiarli.
 *
 * Il punto che conta: **è la sotto-modalità a decidere cosa fa lo scatto**, non il comando. Se
 * la camera è in panoramica, `TAKE_PICTURE` produce una panoramica anche se il comando inviato
 * è identico a quello di uno scatto singolo. Per questo l'app la imposta invece di darla per
 * buona.
 */
enum class CameraMode(
    val label: String,
    /** Tipo di opzione da elencare in `SetOptions.option_types`. */
    val optionType: Int,
    /** Campo corrispondente dentro `Options`. */
    val optionField: Int,
    val subMode: Int,
    val functionMode: Int,
    /** Non nullo = si registra con `START_CAPTURE` passando questo `CaptureMode`. */
    val captureMode: Int?,
) {
    FOTO(
        label = "Foto",
        optionType = LunaProtocolCodes.OptionType.PHOTO_SUB_MODE,
        optionField = LunaProtocolCodes.OptionsField.PHOTO_SUB_MODE,
        subMode = LunaProtocolCodes.PhotoSubMode.SINGLE,
        functionMode = LunaProtocolCodes.FunctionMode.NORMAL_IMAGE,
        captureMode = null,
    ),
    PANORAMA(
        label = "Panorama",
        optionType = LunaProtocolCodes.OptionType.PHOTO_SUB_MODE,
        optionField = LunaProtocolCodes.OptionsField.PHOTO_SUB_MODE,
        subMode = LunaProtocolCodes.PhotoSubMode.INSTA_PANO,
        functionMode = LunaProtocolCodes.FunctionMode.NORMAL_POWER_PANO_IMAGE,
        captureMode = null,
    ),
    VIDEO(
        label = "Video",
        optionType = LunaProtocolCodes.OptionType.VIDEO_SUB_MODE,
        optionField = LunaProtocolCodes.OptionsField.VIDEO_SUB_MODE,
        subMode = LunaProtocolCodes.VideoSubMode.NORMAL,
        functionMode = LunaProtocolCodes.FunctionMode.NORMAL_VIDEO,
        captureMode = LunaProtocolCodes.CaptureMode.NORMAL,
    ),
    PURE_VIDEO(
        label = "PureVideo",
        optionType = LunaProtocolCodes.OptionType.VIDEO_SUB_MODE,
        optionField = LunaProtocolCodes.OptionsField.VIDEO_SUB_MODE,
        subMode = LunaProtocolCodes.VideoSubMode.PURE,
        functionMode = LunaProtocolCodes.FunctionMode.PURE_VIDEO,
        captureMode = LunaProtocolCodes.CaptureMode.PURE_VIDEO,
    ),
    SLOW_MOTION(
        label = "Slow-motion",
        optionType = LunaProtocolCodes.OptionType.VIDEO_SUB_MODE,
        optionField = LunaProtocolCodes.OptionsField.VIDEO_SUB_MODE,
        subMode = LunaProtocolCodes.VideoSubMode.SLOW_MOTION,
        functionMode = LunaProtocolCodes.FunctionMode.SLOWMOTION_VIDEO,
        captureMode = LunaProtocolCodes.CaptureMode.SLOW_MOTION,
    ),
    TIMELAPSE(
        label = "Timelapse",
        optionType = LunaProtocolCodes.OptionType.VIDEO_SUB_MODE,
        optionField = LunaProtocolCodes.OptionsField.VIDEO_SUB_MODE,
        subMode = LunaProtocolCodes.VideoSubMode.TIMELAPSE,
        functionMode = LunaProtocolCodes.FunctionMode.MOBILE_TIMELAPSE,
        captureMode = LunaProtocolCodes.CaptureMode.NORMAL,
    ),
    ;

    /** Le modalità fotografiche scattano con `TAKE_PICTURE`, le altre registrano. */
    val isPhoto: Boolean get() = captureMode == null

    /** La panoramica è l'unica con la scelta fra sferica e 2:1. */
    val hasPanoAspect: Boolean get() = this == PANORAMA

    companion object {
        /**
         * In che modalità è la camera, dalle due sotto-modalità che riporta.
         *
         * Il video vince quando riporta qualcosa di diverso da `VIDEO_NONE`: la camera lascia
         * l'altra sotto-modalità al suo valore sentinella invece di azzerarla.
         */
        fun fromSubModes(photoSubMode: Int?, videoSubMode: Int?): CameraMode? {
            if (videoSubMode != null && videoSubMode != LunaProtocolCodes.VideoSubMode.NONE) {
                return entries.firstOrNull {
                    it.optionField == LunaProtocolCodes.OptionsField.VIDEO_SUB_MODE && it.subMode == videoSubMode
                }
            }
            if (photoSubMode != null && photoSubMode != LunaProtocolCodes.PhotoSubMode.NONE) {
                return entries.firstOrNull {
                    it.optionField == LunaProtocolCodes.OptionsField.PHOTO_SUB_MODE && it.subMode == photoSubMode
                }
            }
            return null
        }
    }
}
