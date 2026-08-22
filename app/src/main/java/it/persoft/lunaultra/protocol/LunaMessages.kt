package it.persoft.lunaultra.protocol

import it.persoft.lunaultra.data.PhotoSettings
import it.persoft.lunaultra.data.VideoSettings

/**
 * Composizione dei corpi protobuf dei comandi.
 *
 * Sono funzioni pure — byte in uscita, nessuna sessione — così i payload si possono confrontare
 * nei test con quelli osservati nel traffico dell'app ufficiale.
 */
object LunaMessages {

    /** `GetOptions { repeated OptionType option_types = 1 }` */
    fun getOptions(vararg types: Int): ByteArray {
        val writer = ProtoWriter()
        types.forEach { writer.int32(FIELD_OPTION_TYPES, it) }
        return writer.toByteArray()
    }

    /** `StartCapture { CaptureMode mode = 1 }` */
    fun startCapture(mode: Int): ByteArray = ProtoWriter().int32(1, mode).toByteArray()

    /** `StopCapture { ExtraMetadata extra_metadata = 1; CaptureMode mode = 2 }` */
    fun stopCapture(mode: Int): ByteArray = ProtoWriter().int32(2, mode).toByteArray()

    /** `StartTimelapse` / `StopTimelapse`, entrambi `{ TimelapseMode mode = 1 }` */
    fun timelapseMode(mode: Int): ByteArray = ProtoWriter().int32(1, mode).toByteArray()

    /**
     * `SetTimelapseOptions { TimelapseOptions timelapse_options = 1; TimelapseMode mode = 2 }`
     * con `TimelapseOptions { uint32 duration = 1; uint32 lapseTime = 2 }`.
     */
    fun setTimelapseOptions(durationSeconds: Int, intervalSeconds: Int, mode: Int): ByteArray =
        ProtoWriter()
            .message(1) {
                int32(LunaProtocolCodes.TimelapseOptionsField.DURATION, durationSeconds.coerceAtLeast(0))
                int32(LunaProtocolCodes.TimelapseOptionsField.LAPSE_TIME, intervalSeconds.coerceAtLeast(1))
            }
            .int32(2, mode)
            .toByteArray()

    /**
     * `TakePicture { Mode mode = 1; ...; bool isInstaPanoEnabled = 5 }`
     *
     * `Mode` è `TakePicture.Mode`, non `CaptureMode`: qui lo scatto normale è 0, mentre 1 è il
     * bracketing di esposizione. Sono due enum diversi con nomi simili.
     */
    fun takePicture(mode: Int, instaPano: Boolean = false): ByteArray {
        val writer = ProtoWriter().int32(1, mode)
        // Il campo si manda solo quando serve: la panoramica la decide la sotto-modalità della
        // camera, e ripetere `false` a ogni scatto è un modo di litigarci.
        if (instaPano) writer.bool(5, true)
        return writer.toByteArray()
    }

    /**
     * `SetOptions { repeated OptionType option_types = 1; Options value = 2 }` con un solo
     * campo dentro `Options`. È così che si cambia modalità: sotto-modalità foto o video.
     */
    fun setOption(optionType: Int, field: Int, value: Int): ByteArray =
        ProtoWriter()
            .int32(FIELD_OPTION_TYPES, optionType)
            .message(FIELD_OPTIONS_VALUE) { int32(field, value) }
            .toByteArray()

    /**
     * `SetPhotographyOptions { repeated PhotographyOptionType option_types = 1;
     * PhotographyOptions value = 2; FunctionMode function_mode = 3 }`.
     *
     * Il `function_mode` non è decorativo: le impostazioni fotografiche sono memorizzate per
     * modalità, e senza dirlo si scrive nella modalità sbagliata.
     */
    fun setPhotographyOption(optionType: Int, field: Int, value: Int, functionMode: Int): ByteArray =
        ProtoWriter()
            .int32(1, optionType)
            .message(2) { int32(field, value) }
            .int32(3, functionMode)
            .toByteArray()

    /**
     * Le regolazioni del pannello Foto in una sola richiesta. Riduce il ritardo prima dello
     * scatto e impedisce che modalità e singole opzioni si applichino in ordine parziale.
     */
    fun setPhotoControls(value: PhotoSettings, functionMode: Int): ByteArray {
        val manual = value.proMode
        val kelvin = if (manual) value.whiteBalanceKelvin else 0
        return ProtoWriter()
            .int32(1, LunaProtocolCodes.PhotographyOptionType.BRIGHTNESS)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.EXPOSURE_BIAS)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.WHITE_BALANCE)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.RAW_CAPTURE_TYPE)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.WHITE_BALANCE_VALUE)
            .message(2) {
                sint32(LunaProtocolCodes.PhotographyOptionsField.BRIGHTNESS, if (manual) value.brightness else 0)
                sint32(
                    LunaProtocolCodes.PhotographyOptionsField.EXPOSURE_BIAS,
                    if (manual) value.exposureBiasThirds else 0,
                )
                int32(
                    LunaProtocolCodes.PhotographyOptionsField.WHITE_BALANCE,
                    if (kelvin == 0) LunaProtocolCodes.WhiteBalance.AUTO
                    else LunaProtocolCodes.WhiteBalance.MANUAL_KELVIN,
                )
                int32(LunaProtocolCodes.PhotographyOptionsField.RAW_CAPTURE_TYPE, value.rawCaptureType)
                int32(LunaProtocolCodes.PhotographyOptionsField.WHITE_BALANCE_VALUE, kelvin)
            }
            .int32(3, functionMode)
            .toByteArray()
    }

    /**
     * Imposta la combinazione risoluzione/FPS della registrazione. Nel protocollo non sono due
     * valori indipendenti: entrambi vivono nell'enum `VideoResolution` del campo 31.
     */
    fun setVideoProfile(profileCode: Int, functionMode: Int): ByteArray =
        setPhotographyOption(
            optionType = LunaProtocolCodes.PhotographyOptionType.RECORD_RESOLUTION,
            field = LunaProtocolCodes.PhotographyOptionsField.RECORD_RESOLUTION,
            value = profileCode,
            functionMode = functionMode,
        )

    /**
     * Regolazioni Pro video rimisurate sulla Luna Ultra. ISO e otturatore vivono nei due
     * `ExposureOptions` (campi 20/21): il vecchio `exposure_manual` viene accettato ma poi
     * torna al valore precedente, quindi qui non viene usato.
     */
    fun setVideoControls(value: VideoSettings, functionMode: Int): ByteArray {
        val pro = value.proMode
        val iso = if (pro) value.iso else 0
        val shutter = if (pro) value.shutterSeconds else 0.0
        val program = when {
            iso == 0 && shutter == 0.0 -> LunaProtocolCodes.ExposureProgram.AUTO
            iso == 0 -> LunaProtocolCodes.ExposureProgram.SHUTTER_PRIORITY
            shutter == 0.0 -> LunaProtocolCodes.ExposureProgram.ISO_PRIORITY
            else -> LunaProtocolCodes.ExposureProgram.MANUAL
        }
        val exposureMode = if (program == LunaProtocolCodes.ExposureProgram.MANUAL) {
            LunaProtocolCodes.ExposureMode.MANUAL
        } else {
            LunaProtocolCodes.ExposureMode.AUTO
        }
        val kelvin = if (pro) value.whiteBalanceKelvin else 0

        return ProtoWriter()
            .int32(1, LunaProtocolCodes.PhotographyOptionType.RECORD_RESOLUTION)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.EXPOSURE_MODE)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.STILL_EXPOSURE_OPTIONS)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.VIDEO_EXPOSURE_OPTIONS)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.EXPOSURE_BIAS)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.WHITE_BALANCE)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.WHITE_BALANCE_VALUE)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.SHARPNESS)
            .message(2) {
                int32(LunaProtocolCodes.PhotographyOptionsField.RECORD_RESOLUTION, value.profileCode)
                int32(LunaProtocolCodes.PhotographyOptionsField.EXPOSURE_MODE, exposureMode)
                message(LunaProtocolCodes.PhotographyOptionsField.STILL_EXPOSURE_OPTIONS) {
                    int32(LunaProtocolCodes.ExposureOptionsField.PROGRAM, program)
                    int32(LunaProtocolCodes.ExposureOptionsField.ISO, iso)
                    double(LunaProtocolCodes.ExposureOptionsField.SHUTTER_SPEED, shutter)
                }
                message(LunaProtocolCodes.PhotographyOptionsField.VIDEO_EXPOSURE_OPTIONS) {
                    int32(LunaProtocolCodes.ExposureOptionsField.PROGRAM, program)
                    int32(LunaProtocolCodes.ExposureOptionsField.ISO, iso)
                    double(LunaProtocolCodes.ExposureOptionsField.SHUTTER_SPEED, shutter)
                }
                sint32(
                    LunaProtocolCodes.PhotographyOptionsField.EXPOSURE_BIAS,
                    if (pro) value.exposureBiasThirds else 0,
                )
                int32(
                    LunaProtocolCodes.PhotographyOptionsField.WHITE_BALANCE,
                    if (kelvin == 0) LunaProtocolCodes.WhiteBalance.AUTO else LunaProtocolCodes.WhiteBalance.MANUAL_KELVIN,
                )
                int32(LunaProtocolCodes.PhotographyOptionsField.WHITE_BALANCE_VALUE, kelvin)
                int32(LunaProtocolCodes.PhotographyOptionsField.SHARPNESS, value.sharpness)
            }
            .int32(3, functionMode)
            .toByteArray()
    }

    /** Il colore va scritto da solo: affiancargli gamma/filter impedisce alla camera di applicarlo. */
    fun setVideoColorMode(colorMode: Int, functionMode: Int): ByteArray =
        setPhotographyOption(
            LunaProtocolCodes.PhotographyOptionType.COLOR_MODE,
            LunaProtocolCodes.PhotographyOptionsField.COLOR_MODE,
            colorMode,
            functionMode,
        )

    /** Un filtro invece deve ri-affermare il colore nello stesso messaggio per applicarsi subito. */
    fun setVideoFilter(filter: Int, colorMode: Int, functionMode: Int): ByteArray =
        ProtoWriter()
            .int32(1, LunaProtocolCodes.PhotographyOptionType.VIDEO_GAMMA_MODE)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.COLOR_MODE)
            .message(2) {
                int32(LunaProtocolCodes.PhotographyOptionsField.VIDEO_GAMMA_MODE, filter)
                int32(LunaProtocolCodes.PhotographyOptionsField.COLOR_MODE, colorMode)
            }
            .int32(3, functionMode)
            .toByteArray()

    fun setVideoFilterIntensity(intensity: Int, colorMode: Int, functionMode: Int): ByteArray =
        ProtoWriter()
            .int32(1, LunaProtocolCodes.PhotographyOptionType.FILTER_INTENSITY)
            .int32(1, LunaProtocolCodes.PhotographyOptionType.COLOR_MODE)
            .message(2) {
                int32(LunaProtocolCodes.PhotographyOptionsField.FILTER_INTENSITY, intensity)
                int32(LunaProtocolCodes.PhotographyOptionsField.COLOR_MODE, colorMode)
            }
            .int32(3, functionMode)
            .toByteArray()

    /**
     * `StartLiveStream`, con i campi:
     * `2 enableVideo, 6 videoBitrate, 7 resolution, 8 enableGyro, 9 videoBitrate1, 10 resolution1`.
     *
     * I valori sono quelli di una cattura funzionante dell'app ufficiale — risoluzione 9 è
     * `RES_1440_720P30`, 18 è `RES_480_240P30` — e sono lasciati identici di proposito: se la
     * camera rifiuta, significa che non è d'accordo lei, non che abbiamo tirato a indovinare.
     */
    fun startLiveStream(): ByteArray =
        ProtoWriter()
            .int32(2, 1)
            .int32(6, 40)
            .int32(7, 9)
            .int32(8, 1)
            .int32(9, 40)
            .int32(10, 18)
            .toByteArray()

    /** Corpo del comando gimbal: due velocità con segno. Forma da confermare sulla camera. */
    fun gimbalVelocity(panField: Int, panValue: Int, tiltField: Int, tiltValue: Int): ByteArray =
        ProtoWriter()
            .sint32(panField, panValue)
            .sint32(tiltField, tiltValue)
            .toByteArray()

    /**
     * `GetFileList { MediaType media_type = 1; uint32 start = 2; uint32 limit = 3 }`
     *
     * La risposta è `GetFileListResp { repeated string uri = 1; uint32 total_count = 2 }`.
     * L'elenco arriva a pagine: il totale dichiarato serve a sapere quando fermarsi.
     */
    fun getFileList(mediaType: Int, start: Int, limit: Int): ByteArray =
        ProtoWriter()
            .int32(1, mediaType)
            .int32(2, start)
            .int32(3, limit)
            .toByteArray()

    /** `GetMiniThumbnail { string uri = 1 }` — la miniatura di un file, per percorso completo. */
    fun getMiniThumbnail(uri: String): ByteArray = ProtoWriter().string(1, uri).toByteArray()

    /** `DeleteFiles { repeated string uri = 1 }` */
    fun deleteFiles(uris: List<String>): ByteArray {
        val writer = ProtoWriter()
        uris.forEach { writer.string(1, it) }
        return writer.toByteArray()
    }

    /** Campo di `GetOptions`/`SetOptions` che elenca i tipi di opzione richiesti. */
    const val FIELD_OPTION_TYPES = 1

    /** Campo di `GetOptionsResp` che contiene il messaggio `Options`. */
    const val FIELD_OPTIONS_VALUE = 2
}
