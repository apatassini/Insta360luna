package it.persoft.lunaultra.protocol

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

    /** `TakePicture { CaptureMode mode = 1 }` */
    fun takePicture(mode: Int): ByteArray = ProtoWriter().int32(1, mode).toByteArray()

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

    /** Campo di `GetOptions`/`SetOptions` che elenca i tipi di opzione richiesti. */
    const val FIELD_OPTION_TYPES = 1

    /** Campo di `GetOptionsResp` che contiene il messaggio `Options`. */
    const val FIELD_OPTIONS_VALUE = 2
}
