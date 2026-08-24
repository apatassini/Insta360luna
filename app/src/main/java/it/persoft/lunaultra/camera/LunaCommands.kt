package it.persoft.lunaultra.camera

import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.PhotoSettings
import it.persoft.lunaultra.data.LunaVideoProfiles
import it.persoft.lunaultra.data.VideoSettings
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.protocol.LunaMessages
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.LunaProtocolCodes.BatteryField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.CaptureStatusField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.OptionType
import it.persoft.lunaultra.protocol.LunaProtocolCodes.OptionsField
import it.persoft.lunaultra.protocol.LunaProtocolCodes.StorageField
import it.persoft.lunaultra.media.Jpeg
import it.persoft.lunaultra.protocol.ProtoField
import it.persoft.lunaultra.protocol.ProtoReader
import it.persoft.lunaultra.protocol.Ucd2Frame
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.roundToInt

private val CINEMATIC_FILTERS = setOf(
    LunaProtocolCodes.Filter.POS_FILM,
    LunaProtocolCodes.Filter.NEG_FILM,
    LunaProtocolCodes.Filter.CC_FILM,
    LunaProtocolCodes.Filter.NC_FILM,
    LunaProtocolCodes.Filter.FRESH,
    LunaProtocolCodes.Filter.CINEMATIC,
)

/**
 * I comandi della camera, composti sui messaggi protobuf reali del namespace
 * `insta360.messages`.
 *
 * Anche il gimbal usa ora il comando e il payload confermati dalle catture Luna Ultra del
 * progetto Insta360Linker; non dipende più dallo scanner diagnostico.
 */
class LunaCommands(
    private val session: CameraSession,
    private val settings: StateFlow<AppSettings>,
    private val log: EventLog,
) {

    /** Nella risposta `GetOptionsResp` il messaggio `Options` sta nel campo 2. */
    private fun optionsReader(frame: Ucd2Frame): ProtoReader = ProtoReader(frame.payload)

    suspend fun fetchCameraInfo(): Result<CameraStatus> =
        session.request(
            LunaProtocolCodes.GET_OPTIONS,
            LunaMessages.getOptions(
                OptionType.CAMERA_TYPE,
                OptionType.SERIAL_NUMBER,
                OptionType.FIRMWARE_REVISION,
            ),
        ).map { frame ->
            val reader = optionsReader(frame)
            CameraStatus(
                model = reader.stringOrNull(OPTIONS, OptionsField.CAMERA_TYPE),
                serial = reader.stringOrNull(OPTIONS, OptionsField.SERIAL_NUMBER),
                firmware = reader.stringOrNull(OPTIONS, OptionsField.FIRMWARE_REVISION),
                lastUpdateMs = System.currentTimeMillis(),
                rawDump = frame.describePayload(),
            )
        }

    /**
     * Legge SSID e password direttamente dalla camera quando la sessione Wi-Fi è già aperta.
     * È lo stesso dato che l'app ufficiale riceve durante l'associazione; serve a trasformare
     * una sola connessione manuale in connessioni automatiche successive.
     */
    suspend fun fetchWifiInfo(): Result<CameraWifiInfo> =
        session.request(
            LunaProtocolCodes.GET_OPTIONS,
            LunaMessages.getOptions(OptionType.WIFI_INFO),
        ).map { frame ->
            val reader = optionsReader(frame)
            CameraWifiInfo(
                ssid = reader.stringOrNull(
                    OPTIONS,
                    OptionsField.WIFI_INFO,
                    LunaProtocolCodes.WifiInfoField.SSID,
                ),
                password = reader.stringOrNull(
                    OPTIONS,
                    OptionsField.WIFI_INFO,
                    LunaProtocolCodes.WifiInfoField.PASSWORD,
                ),
            )
        }

    /**
     * Stato corrente: batteria e storage da `GET_OPTIONS`, registrazione da
     * `GET_CURRENT_CAPTURE_STATUS`. Sono due comandi distinti perché lo stato di cattura non
     * è un'opzione memorizzata.
     */
    suspend fun fetchStatus(): Result<CameraStatus> {
        val options = session.request(
            LunaProtocolCodes.GET_OPTIONS,
            LunaMessages.getOptions(OptionType.BATTERY_STATUS, OptionType.STORAGE_STATE),
        )
        val optionsFrame = options.getOrElse { return Result.failure(it) }
        val reader = optionsReader(optionsFrame)

        val level = reader.intOrNull(OPTIONS, OptionsField.BATTERY_STATUS, BatteryField.BATTERY_LEVEL)
        val scale = reader.intOrNull(OPTIONS, OptionsField.BATTERY_STATUS, BatteryField.BATTERY_SCALE)
        val free = reader.longOrNull(OPTIONS, OptionsField.STORAGE_STATE, StorageField.FREE_SPACE)
        val total = reader.longOrNull(OPTIONS, OptionsField.STORAGE_STATE, StorageField.TOTAL_SPACE)

        val capture = fetchCaptureState()

        return Result.success(
            CameraStatus(
                batteryPercent = batteryPercent(level, scale),
                recording = capture?.let { LunaProtocolCodes.CaptureState.isRecording(it.state) },
                captureMode = capture?.let { LunaProtocolCodes.CaptureState.name(it.state) },
                captureSeconds = capture?.seconds,
                freeSpaceBytes = free,
                totalSpaceBytes = total,
                lastUpdateMs = System.currentTimeMillis(),
                rawDump = optionsFrame.describePayload(),
            )
        )
    }

    /**
     * `battery_scale` è il fondo scala del livello. Quando la camera non lo manda vale 100,
     * ma non lo si può dare per scontato: uno scale a 255 renderebbe la percentuale assurda.
     */
    private fun batteryPercent(level: Int?, scale: Int?): Int? {
        if (level == null) return null
        val fullScale = scale?.takeIf { it > 0 } ?: 100
        return ((level.toFloat() / fullScale) * 100f).roundToInt().coerceIn(0, 100)
    }

    /**
     * Aspetta che la camera abbia finito di salvare, prima di muovere il gimbal.
     *
     * Il comando di scatto risponde appena la camera lo accetta, non quando il file è sulla
     * scheda: fra le due cose passa più di un secondo su una foto da otto megapixel. Chi scatta
     * una sequenza e va avanti sulla risposta chiede lo scatto successivo mentre il precedente
     * si sta ancora scrivendo, e la camera lo lascia cadere senza dirlo — su una panoramica da
     * ventiquattro scatti ne sono arrivati tredici, e la seconda metà del piano si è ritrovata
     * senza foto.
     *
     * Quindi si aspetta lo stato: la camera dichiara di stare catturando finché non ha finito.
     * Se non risponde o resta occupata oltre il tempo massimo si va avanti lo stesso — meglio
     * uno scatto perso che una sequenza che si pianta — e chi legge il log lo vede.
     */
    suspend fun awaitCaptureIdle(timeoutMs: Long = CAPTURE_IDLE_TIMEOUT_MS): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val snapshot = fetchCaptureState()
            if (snapshot != null && !LunaProtocolCodes.CaptureState.isBusy(snapshot.state)) return true
            delay(CAPTURE_IDLE_POLL_MS)
        }
        return false
    }

    private suspend fun fetchCaptureState(): CaptureSnapshot? =
        session.request(LunaProtocolCodes.GET_CURRENT_CAPTURE_STATUS).map { frame ->
            parseCaptureStatus(frame.payload, nested = true)
        }.getOrNull()

    /**
     * Legge `CameraCaptureStatus`. Nella risposta a `GET_CURRENT_CAPTURE_STATUS` è annidato nel
     * campo 1 di `GetCurrentCaptureStatusResp`; nella notifica 8208 arriva invece nudo.
     */
    fun parseCaptureStatus(payload: ByteArray, nested: Boolean): CaptureSnapshot {
        val reader = ProtoReader(payload)
        val path = if (nested) intArrayOf(1) else IntArray(0)
        val state = reader.intOrNull(*(path + CaptureStatusField.STATE)) ?: 0
        val seconds = reader.intOrNull(*(path + CaptureStatusField.CAPTURE_TIME))
        return CaptureSnapshot(state = state, seconds = seconds)
    }

    fun statusFromNotification(frame: Ucd2Frame): CameraStatus? = when (frame.code) {
        LunaProtocolCodes.NOTIFICATION_CURRENT_CAPTURE_STATUS -> {
            val snapshot = parseCaptureStatus(frame.payload, nested = false)
            CameraStatus(
                recording = LunaProtocolCodes.CaptureState.isRecording(snapshot.state),
                captureMode = LunaProtocolCodes.CaptureState.name(snapshot.state),
                captureSeconds = snapshot.seconds,
                lastUpdateMs = System.currentTimeMillis(),
            )
        }

        LunaProtocolCodes.NOTIFICATION_BATTERY_UPDATE -> {
            val reader = ProtoReader(frame.payload)
            val level = reader.intOrNull(BatteryField.BATTERY_LEVEL)
            val scale = reader.intOrNull(BatteryField.BATTERY_SCALE)
            batteryPercent(level, scale)?.let {
                CameraStatus(batteryPercent = it, lastUpdateMs = System.currentTimeMillis())
            }
        }

        else -> null
    }

    // ---- Modalità della camera ----

    /**
     * Porta la camera nella modalità richiesta.
     *
     * Serve perché il comando di scatto non dice cosa scattare: se la camera è rimasta in
     * panoramica, `TAKE_PICTURE` fa una panoramica. Impostare la sotto-modalità prima è l'unico
     * modo di sapere cosa si ottiene.
     */
    suspend fun applyMode(mode: CameraMode): Result<Unit> =
        session.request(
            LunaProtocolCodes.SET_OPTIONS,
            LunaMessages.setOption(mode.optionType, mode.optionField, mode.subMode),
        ).map { log.info("Modalità camera impostata: ${mode.label}") }

    /**
     * Sferica o 2:1. Sulla Luna Ultra la panoramica è una sola sotto-modalità e la scelta fra
     * le due proporzioni viaggia su `PANO_ASPECT`, sotto il function mode della panoramica.
     */
    suspend fun setPanoAspect(aspect: Int): Result<Unit> =
        session.request(
            LunaProtocolCodes.SET_PHOTOGRAPHY_OPTIONS,
            LunaMessages.setPhotographyOption(
                optionType = LunaProtocolCodes.PhotographyOptionType.PANO_ASPECT,
                field = LunaProtocolCodes.PhotographyOptionsField.PANO_ASPECT,
                value = aspect,
                functionMode = LunaProtocolCodes.FunctionMode.NORMAL_POWER_PANO_IMAGE,
            ),
        ).map { }

    /** Timer escluso: quello è gestito localmente per mostrare un conto alla rovescia preciso. */
    suspend fun applyPhotoSettings(value: PhotoSettings, mode: CameraMode): Result<Unit> =
        session.request(
            LunaProtocolCodes.SET_PHOTOGRAPHY_OPTIONS,
            LunaMessages.setPhotoControls(value, mode.functionMode),
        ).map { }

    /** Vale per foto e video: cambia il function mode, non il formato del messaggio. */
    suspend fun setZoomScale(scale: Int, mode: CameraMode): Result<Unit> =
        session.request(
            LunaProtocolCodes.SET_PHOTOGRAPHY_OPTIONS,
            LunaMessages.setZoomScale(scale, mode.functionMode),
        ).map { }

    suspend fun applyVideoSettings(value: VideoSettings, mode: CameraMode): Result<Unit> {
        session.request(
            LunaProtocolCodes.SET_PHOTOGRAPHY_OPTIONS,
            LunaMessages.setVideoControls(value, mode.functionMode),
        ).getOrElse { return Result.failure(it) }

        // Misurato sulla camera: il cambio colore deve viaggiare da solo.
        session.request(
            LunaProtocolCodes.SET_PHOTOGRAPHY_OPTIONS,
            LunaMessages.setVideoColorMode(value.colorMode, mode.functionMode),
        ).getOrElse { return Result.failure(it) }

        val profile = LunaVideoProfiles.all.firstOrNull { it.code == value.profileCode }
        val filtersAvailable = value.colorMode != LunaProtocolCodes.ColorMode.DOLBY_VISION &&
            profile != null && profile.width <= 3840 && profile.fps <= 60
        if (!filtersAvailable) return Result.success(Unit)

        // Il filtro è l'inverso: deve portare con sé il colore per ricostruire subito la pipeline.
        session.request(
            LunaProtocolCodes.SET_PHOTOGRAPHY_OPTIONS,
            LunaMessages.setVideoFilter(value.filter, value.colorMode, mode.functionMode),
        ).getOrElse { return Result.failure(it) }

        if (value.filter in CINEMATIC_FILTERS) {
            session.request(
                LunaProtocolCodes.SET_PHOTOGRAPHY_OPTIONS,
                LunaMessages.setVideoFilterIntensity(value.filterIntensity, value.colorMode, mode.functionMode),
            ).getOrElse { return Result.failure(it) }
        }
        return Result.success(Unit)
    }

    /** In che modalità è adesso la camera, letta dalle due sotto-modalità che riporta. */
    suspend fun fetchCameraMode(): Result<CameraMode?> =
        session.request(
            LunaProtocolCodes.GET_OPTIONS,
            LunaMessages.getOptions(OptionType.PHOTO_SUB_MODE, OptionType.VIDEO_SUB_MODE),
        ).map { frame ->
            val reader = optionsReader(frame)
            CameraMode.fromSubModes(
                photoSubMode = reader.intOrNull(OPTIONS, OptionsField.PHOTO_SUB_MODE),
                videoSubMode = reader.intOrNull(OPTIONS, OptionsField.VIDEO_SUB_MODE),
            )
        }

    // ---- Registrazione ----

    /** `StartCapture { CaptureMode mode = 1 }` */
    suspend fun startCapture(captureMode: Int = LunaProtocolCodes.CaptureMode.NORMAL): Result<Unit> =
        session.request(
            LunaProtocolCodes.START_CAPTURE,
            LunaMessages.startCapture(captureMode),
        ).map { }

    /** `StopCapture { ExtraMetadata extra_metadata = 1; CaptureMode mode = 2 }` */
    suspend fun stopCapture(captureMode: Int = LunaProtocolCodes.CaptureMode.NORMAL): Result<Unit> =
        session.request(
            LunaProtocolCodes.STOP_CAPTURE,
            LunaMessages.stopCapture(captureMode),
        ).map { }

    /**
     * Uno scatto. [instaPano] alza `isInstaPanoEnabled`, che chiede la panoramica per questo
     * scatto anche senza cambiare la sotto-modalità della camera.
     *
     * Il modo è `TakePicture.Mode.NORMAL`, che vale **0**: il valore 1 di quell'enum è l'AEB,
     * il bracketing di esposizione, ed è quello che partiva finché qui si passava per sbaglio
     * la costante di `CaptureMode`.
     */
    suspend fun takePicture(instaPano: Boolean = false): Result<Unit> =
        session.request(
            LunaProtocolCodes.TAKE_PICTURE,
            LunaMessages.takePicture(LunaProtocolCodes.TakePictureMode.NORMAL, instaPano),
            timeoutMs = PHOTO_TIMEOUT_MS,
        ).map { }

    // ---- Media sulla camera ----

    /**
     * Una pagina dell'elenco dei file: i percorsi e il totale dichiarato dalla camera.
     *
     * Dal firmware 1.0.238 la camera non espone più l'indice HTTP delle cartelle, che è come si
     * elencavano i file prima: si enumerano da qui, e restano scaricabili per URL finché la
     * sessione di controllo è aperta.
     */
    suspend fun getFileList(start: Int, limit: Int): Result<FileListPage> =
        session.request(
            LunaProtocolCodes.GET_FILE_LIST,
            LunaMessages.getFileList(LunaProtocolCodes.MediaType.VIDEO_AND_PHOTO, start, limit),
            timeoutMs = FILE_LIST_TIMEOUT_MS,
        ).map { frame ->
            val fields = ProtoReader(frame.payload).fields()
            val paths = fields
                .filterIsInstance<ProtoField.LengthDelimited>()
                .filter { it.number == 1 }
                .map { String(it.value, Charsets.UTF_8) }
            val total = fields
                .filterIsInstance<ProtoField.VarInt>()
                .firstOrNull { it.number == 2 }
                ?.value
                ?.toInt()
            FileListPage(paths = paths, total = total)
        }

    /**
     * La miniatura di un file, se la camera la manda.
     *
     * La forma della risposta non è documentata, quindi non si dà per scontata: si cerca un
     * JPEG dentro il corpo, il che funziona sia se arriva nudo sia se è dentro un campo
     * protobuf. Quando non c'è, chi chiama ripiega invece di restare senza immagine.
     */
    suspend fun getMiniThumbnail(uri: String): Result<ByteArray> =
        session.request(
            LunaProtocolCodes.GET_MINI_THUMBNAIL,
            LunaMessages.getMiniThumbnail(uri),
            timeoutMs = THUMBNAIL_TIMEOUT_MS,
        ).mapCatching { frame ->
            Jpeg.extract(frame.payload)
                ?: throw IllegalStateException("nessuna miniatura nella risposta")
        }

    /** Cancella file dalla camera. Irreversibile: la conferma la chiede chi chiama. */
    suspend fun deleteFiles(uris: List<String>): Result<Unit> =
        session.request(
            LunaProtocolCodes.DELETE_FILES,
            LunaMessages.deleteFiles(uris),
            timeoutMs = DELETE_TIMEOUT_MS,
        ).map { }

    // ---- Anteprima dal vivo ----

    /**
     * Chiede alla camera di aprire lo stream di anteprima. Il video non arriva come risposta:
     * viene spinto sui frame media della stessa sessione, che [CameraSession.videoFrames]
     * ripubblica.
     */
    suspend fun startLiveStream(): Result<Unit> =
        session.request(LunaProtocolCodes.START_LIVE_STREAM, LunaMessages.startLiveStream()).map { }

    suspend fun stopLiveStream(): Result<Unit> =
        session.request(LunaProtocolCodes.STOP_LIVE_STREAM).map { }

    // ---- Timelapse ----

    /**
     * `SetTimelapseOptions { TimelapseOptions timelapse_options = 1; TimelapseMode mode = 2 }`
     *
     * `duration` è la durata totale in secondi (0 = illimitata), `lapseTime` l'intervallo fra
     * uno scatto e il successivo, sempre in secondi.
     */
    suspend fun setTimelapseOptions(durationSeconds: Int, intervalSeconds: Int): Result<Unit> {
        val body = LunaMessages.setTimelapseOptions(
            durationSeconds = durationSeconds,
            intervalSeconds = intervalSeconds,
            mode = settings.value.timelapseMode,
        )
        return session.request(LunaProtocolCodes.SET_TIMELAPSE_OPTIONS, body).map { }
    }

    /** `StartTimelapse { TimelapseMode mode = 1 }` */
    suspend fun startTimelapse(): Result<Unit> =
        session.request(
            LunaProtocolCodes.START_TIMELAPSE,
            LunaMessages.timelapseMode(settings.value.timelapseMode),
        ).map { }

    /** `StopTimelapse { TimelapseMode mode = 1 }` */
    suspend fun stopTimelapse(): Result<Unit> =
        session.request(
            LunaProtocolCodes.STOP_TIMELAPSE,
            LunaMessages.timelapseMode(settings.value.timelapseMode),
        ).map { }

    /**
     * Avvia la ripresa secondo la modalità scelta. Il timelapse della camera e la registrazione
     * normale sono due comandi diversi: con il movimento del gimbal gestito dall'app, la
     * registrazione normale è spesso la scelta giusta (il timelapse interno accelera i tempi
     * e rende difficile far coincidere durata reale e durata della sequenza).
     */
    suspend fun startRecording(
        useCameraTimelapse: Boolean,
        captureMode: Int = LunaProtocolCodes.CaptureMode.NORMAL,
    ): Result<Unit> =
        if (useCameraTimelapse) startTimelapse() else startCapture(captureMode)

    suspend fun stopRecording(
        useCameraTimelapse: Boolean,
        captureMode: Int = LunaProtocolCodes.CaptureMode.NORMAL,
    ): Result<Unit> =
        if (useCameraTimelapse) stopTimelapse() else stopCapture(captureMode)

    // ---- Gimbal ----

    /**
     * Movimento a velocità: [panPercent] e [tiltPercent] vanno da -1 a +1.
     *
     * Il vettore viene convertito in `-100..100`; [LunaMessages.gimbalMove] applica la rotazione
     * degli assi verificata sul dispositivo e il protobuf ZigZag osservato nei PCAP.
     */
    suspend fun gimbalVelocity(panPercent: Float, tiltPercent: Float): Result<Unit> {
        val cfg = settings.value.gimbal
        val pan = applySign(panPercent.coerceIn(-1f, 1f), cfg.invertPan)
        val tilt = applySign(tiltPercent.coerceIn(-1f, 1f), cfg.invertTilt)
        val payload = LunaMessages.gimbalMove(
            horizontal = (pan * 100f).roundToInt(),
            vertical = (tilt * 100f).roundToInt(),
        )
        return session.fire(LunaProtocolCodes.GIMBAL_CONTROL, payload)
    }

    suspend fun gimbalStop(): Result<Unit> = gimbalVelocity(0f, 0f)

    /**
     * Riporta entrambi gli assi allo zero fisico memorizzato dal firmware della Luna.
     *
     * Quello zero è il fronte del corpo camera, non il centro della corsa: l'intervallo
     * ufficiale è -57°…+235°, quindi lo 0° sta a un sesto della corsa, non a metà. Se la
     * camera è appoggiata con il fronte verso chi la usa, il ritorno a 0° inquadra proprio
     * lui — non è il comando sbagliato, è dove guarda lo zero.
     */
    suspend fun gimbalBackCenter(): Result<Unit> = gimbalAction(LunaMessages.GimbalAction.BACK_CENTER)

    /**
     * Azione del gimbal senza assi, per numero.
     *
     * Serve alla carta *Azioni del gimbal* della Diagnostica: dei valori del campo 1 di
     * `GIMBAL_CONTROL` ne conosciamo due, e l'unico modo onesto di trovarne altri è
     * provarli sulla camera guardando l'anteprima.
     */
    suspend fun gimbalAction(action: Int): Result<Unit> =
        session.request(
            LunaProtocolCodes.GIMBAL_CONTROL,
            LunaMessages.gimbalAction(action),
        ).map { }

    /**
     * Imposta la velocità fisica del gimbal e aggiorna il contesto usato dalla camera.
     * Sono i due comandi consecutivi osservati da Insta360Linker; non è una semplice scala UI.
     */
    suspend fun setGimbalHardwareSpeed(level: Int): Result<Unit> {
        if (level !in 1..3) return Result.failure(IllegalArgumentException("Livello gimbal non valido: $level"))
        val set = session.request(
            LunaProtocolCodes.SET_PHOTOGRAPHY_OPTIONS,
            LunaMessages.setGimbalSpeed(level),
        )
        if (set.isFailure) return Result.failure(set.exceptionOrNull() ?: IllegalStateException("Velocità non applicata"))
        return session.request(
            LunaProtocolCodes.GET_PHOTOGRAPHY_OPTIONS,
            LunaMessages.refreshGimbalSpeed(),
        ).map { }
    }

    /** Livello confermato dalla notifica `0x206A`, se il frame è quello atteso. */
    fun gimbalSpeedFromNotification(frame: Ucd2Frame): Int? {
        if (frame.code != LunaProtocolCodes.NOTIFICATION_GIMBAL_SPEED) return null
        return ProtoReader(frame.payload).intOrNull(2)?.takeIf { it in 1..3 }
    }

    /**
     * Legge lo stato PTZ da una notifica.
     *
     * L'unico riscontro pubblico su questo blocco è il traffico osservato sul codice 8302,
     * compatibile con `CAMERA_NOTIFICATION_PTZ_STATE`: nove varint, di cui due commutano
     * quando il gimbal tocca i finecorsa. Da soli non danno gli angoli, quindi finché i campi
     * non sono confermati questa funzione restituisce ciò che riesce a leggere e nulla più.
     */
    fun parsePtz(frame: Ucd2Frame): PtzState? {
        val cfg = settings.value.gimbal
        val reader = ProtoReader(frame.payload)
        val pan = reader.floatOrNull(cfg.ptzPanField) ?: return null
        val tilt = reader.floatOrNull(cfg.ptzTiltField) ?: 0f
        val scale = if (cfg.angleScale == 0f) 1f else cfg.angleScale
        return PtzState(
            pan = applySign(pan / scale, cfg.invertPan),
            tilt = applySign(tilt / scale, cfg.invertTilt),
            fromCamera = true,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }

    private fun applySign(value: Float, invert: Boolean): Float = if (invert) -value else value

    data class CaptureSnapshot(val state: Int, val seconds: Int?)

    /** Una pagina di `GET_FILE_LIST`: i percorsi letti e quanti ne dichiara in tutto la camera. */
    data class FileListPage(val paths: List<String>, val total: Int?)

    companion object {
        private const val OPTIONS = LunaMessages.FIELD_OPTIONS_VALUE

        /** Uno scatto può richiedere più tempo di un comando qualsiasi: HDR, posa lunga, salvataggio. */
        private const val PHOTO_TIMEOUT_MS = 15_000L

        /** Quanto si aspetta che la camera finisca di salvare prima di muovere il gimbal. */
        private const val CAPTURE_IDLE_TIMEOUT_MS = 8_000L

        /** Ogni quanto si richiede lo stato mentre si aspetta: fitto ma non un martellamento. */
        private const val CAPTURE_IDLE_POLL_MS = 200L

        /** L'elenco dei file su una scheda piena richiede più tempo di un comando qualsiasi. */
        private const val FILE_LIST_TIMEOUT_MS = 12_000L
        /**
         * La miniatura o arriva subito o non arriva. Un'attesa lunga qui blocca la griglia:
         * ottanta caselle per otto secondi sono dieci minuti di caselle vuote.
         */
        private const val THUMBNAIL_TIMEOUT_MS = 2_500L
        private const val DELETE_TIMEOUT_MS = 20_000L
    }
}
