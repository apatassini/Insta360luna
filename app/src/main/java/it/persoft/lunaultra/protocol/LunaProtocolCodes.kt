package it.persoft.lunaultra.protocol

/**
 * Costanti del protocollo di controllo Insta360 (namespace `insta360.messages`).
 *
 * Questi numeri NON sono inventati: provengono dall'estrazione dei `.proto` fatta dai progetti
 * di reverse engineering citati nel README e sono gli stessi usati da client funzionanti sulla
 * Luna Ultra. Ciò che manca è indicato esplicitamente: i comandi del gimbal esistono con un
 * nome ma il loro numero non è pubblico, e per quelli l'app usa lo scanner (vedi [CodeProbe]).
 */
object LunaProtocolCodes {

    // ---- Comandi telefono -> camera (PHONE_COMMAND_*) ----
    const val START_LIVE_STREAM = 1
    const val STOP_LIVE_STREAM = 2
    const val TAKE_PICTURE = 3
    const val START_CAPTURE = 4
    const val STOP_CAPTURE = 5
    const val SET_OPTIONS = 7
    const val GET_OPTIONS = 8
    const val SET_PHOTOGRAPHY_OPTIONS = 9
    const val GET_PHOTOGRAPHY_OPTIONS = 10
    const val GET_CURRENT_CAPTURE_STATUS = 15
    const val GET_TIMELAPSE_OPTIONS = 17
    const val SET_TIMELAPSE_OPTIONS = 18
    const val START_TIMELAPSE = 22
    const val STOP_TIMELAPSE = 23

    /**
     * Codici che la camera mette al posto del comando nelle risposte.
     *
     * L'estrazione descriveva una risposta che rimanda indietro il codice del comando; la Luna
     * Ultra (firmware 1.0.288) fa un'altra cosa: risponde sempre 200, e la correlazione la
     * regge il `requestId`. Il codice 500 esiste nell'estrazione come errore, ma questo
     * firmware non lo usa — a un comando inesistente risponde 200 con corpo vuoto.
     */
    const val RESPONSE_OK = 200
    const val RESPONSE_ERROR = 500

    // ---- Notifiche camera -> telefono (CAMERA_NOTIFICATION_*) ----
    const val NOTIFICATION_BEGIN = 8192
    const val NOTIFICATION_BATTERY_UPDATE = 8195
    const val NOTIFICATION_BATTERY_LOW = 8196
    const val NOTIFICATION_STORAGE_UPDATE = 8198
    const val NOTIFICATION_STORAGE_FULL = 8199
    const val NOTIFICATION_CAPTURE_STOPPED = 8201
    const val NOTIFICATION_CURRENT_CAPTURE_STATUS = 8208
    const val NOTIFICATION_TIMELAPSE_STATUS_UPDATE = 8210

    /**
     * `CAMERA_NOTIFICATION_PTZ_STATE`. Unico numero del blocco gimbal con un riscontro:
     * traffico osservato su questo codice mentre il gimbal si muove, con due campi che
     * commutano 1 -> 0 al raggiungimento dei finecorsa di pan. Trattalo come molto probabile,
     * non come certo.
     */
    const val NOTIFICATION_PTZ_STATE_OBSERVED = 8302

    /** Inizio del blocco dei comandi di fabbrica: non va mai toccato da uno scanner. */
    const val FACTORY_COMMAND_BEGIN = 12288

    /** Blocco `PHONE_REQUEST_*`: dichiarato ma mai popolato dall'estrazione. */
    const val PHONE_REQUEST_BEGIN = 4096

    /** Codici di `insta360.messages.Error.ErrorCode`. */
    object ErrorCode {
        const val UNKNOWN_ERROR = 0
        const val UNKNOWN_MSG_CODE = 1
        const val UNKNOWN_MSG_PAYLOAD = 2
        const val EXECUTE_ERROR = 3
        const val OVER_FILE_NUMBER_LIMIT = 4
        const val SYSTEM_BUSY = 5

        fun name(code: Int): String = when (code) {
            UNKNOWN_ERROR -> "UNKNOWN_ERROR"
            UNKNOWN_MSG_CODE -> "UNKNOWN_MSG_CODE"
            UNKNOWN_MSG_PAYLOAD -> "UNKNOWN_MSG_PAYLOAD"
            EXECUTE_ERROR -> "EXECUTE_ERROR"
            OVER_FILE_NUMBER_LIMIT -> "OVER_FILE_NUMBER_LIMIT"
            SYSTEM_BUSY -> "SYSTEM_BUSY"
            else -> "#$code"
        }
    }

    /** Valori di `insta360.messages.OptionType` usati dall'app. */
    object OptionType {
        const val CAPTURE_TIME_LIMIT = 7
        const val BATTERY_STATUS = 11
        const val LOCAL_TIME = 12
        const val TIME_ZONE = 13
        const val SERIAL_NUMBER = 15
        const val STORAGE_STATE = 20
        const val FIRMWARE_REVISION = 30
        const val VIDEO_SUB_MODE = 41
        const val CAMERA_TYPE = 48

        /**
         * `PTZ_CTRL`. Esiste nell'enum ma il messaggio `Options` non ha un campo 87: sulla
         * Luna Ultra questa opzione risponde vuota, perché il gimbal è un comando a sé.
         * Resta qui per documentare un vicolo cieco già percorso, non per essere usata.
         */
        const val PTZ_CTRL_EMPTY_ON_LUNA = 87
    }

    /** Numeri di campo di `insta360.messages.Options`. */
    object OptionsField {
        const val BATTERY_STATUS = 11
        const val SERIAL_NUMBER = 15
        const val STORAGE_STATE = 20
        const val FIRMWARE_REVISION = 30
        const val VIDEO_SUB_MODE = 41
        const val CAMERA_TYPE = 48
    }

    /** Numeri di campo di `insta360.messages.BatteryStatus`. */
    object BatteryField {
        const val POWER_TYPE = 1
        const val BATTERY_LEVEL = 2
        const val BATTERY_SCALE = 3
    }

    /** Numeri di campo di `insta360.messages.StorageState`. */
    object StorageField {
        const val CARD_STATE = 1
        const val FREE_SPACE = 2
        const val TOTAL_SPACE = 3
    }

    /** Numeri di campo di `insta360.messages.CameraCaptureStatus`. */
    object CaptureStatusField {
        const val STATE = 1
        const val CAPTURE_TIME = 2
        const val CAPTURE_NUMS = 3
    }

    /** Valori di `insta360.messages.CaptureMode`. */
    object CaptureMode {
        const val UNKNOWN = 0
        const val NORMAL = 1
    }

    /** Valori di `insta360.messages.TimelapseMode`. */
    object TimelapseMode {
        const val MIXED = 0
        const val MOBILE_TIMELAPSE_VIDEO = 1
        const val INTERVAL_SHOOTING = 2
        const val STATIC_TIMELAPSE_VIDEO = 3
        const val INTERVAL_VIDEO = 4
        const val STARLAPSE_SHOOTING = 5
    }

    /** Numeri di campo di `insta360.messages.TimelapseOptions`. */
    object TimelapseOptionsField {
        const val DURATION = 1
        const val LAPSE_TIME = 2
        const val OUTPUT_TYPE = 3
        const val ACCELERATE_FREQUENCY = 4
    }

    /** Valori di `insta360.messages.CameraCaptureState`. */
    object CaptureState {
        const val NOT_CAPTURE = 0
        const val NORMAL_CAPTURE = 1
        const val TIMELAPSE_CAPTURE = 2
        const val STATIC_TIMELAPSE_SHOOTING = 11
        const val INTERVAL_VIDEO_CAPTURE = 12

        private val names = mapOf(
            0 to "NOT_CAPTURE", 1 to "NORMAL_CAPTURE", 2 to "TIMELAPSE_CAPTURE",
            3 to "INTERVAL_SHOOTING_CAPTURE", 4 to "SINGLE_SHOOTING", 5 to "HDR_SHOOTING",
            6 to "SELF_TIMER_SHOOTING", 7 to "BULLET_TIME_CAPTURE", 8 to "SETTINGS_NEW_VALUE",
            9 to "HDR_CAPTURE", 10 to "BURST_SHOOTING", 11 to "STATIC_TIMELAPSE_SHOOTING",
            12 to "INTERVAL_VIDEO_CAPTURE", 13 to "TIMESHIFT_CAPTURE", 14 to "AEB_NIGHT_SHOOTING",
            15 to "SINGLE_POWER_PANO_SHOOTING", 16 to "HDR_POWER_PANO_SHOOTING",
            17 to "SUPER_NORMAL_CAPTURE", 18 to "LOOP_RECORDING_CAPTURE", 19 to "STARLAPSE_SHOOTING",
            20 to "FPV_RECORDING_CAPTURE", 21 to "MOVIE_RECORDING_CAPTURE", 22 to "SLOW_MOTION_CAPTURE",
            23 to "SELFIE_RECORDING_CAPTURE", 24 to "PURE_RECORDING_CAPTURE",
        )

        fun name(value: Int): String = names[value] ?: "#$value"

        /** Qualsiasi stato diverso da [NOT_CAPTURE] significa che la camera sta registrando. */
        fun isRecording(value: Int): Boolean = value != NOT_CAPTURE
    }

    /**
     * Comandi che uno scanner non deve mai sfiorare: cancellano file, riavviano la camera,
     * spengono il Wi-Fi o ripristinano le impostazioni di fabbrica. Un corpo vuoto non è una
     * difesa, perché un comando senza argomenti si limita a eseguire.
     */
    val NEVER_SWEEP = setOf(
        6,   // CANCEL_CAPTURE
        12,  // DELETE_FILES
        24,  // ERASE_SD_CARD
        32,  // REBOOT_CAMERA
        33, 34, // OPEN/CLOSE_CAMERA_WIFI
        40,  // CANCEL_AUTHORIZATION
        56,  // SET_STANDBY_MODE
        57,  // RESTORE_FACTORY_SETTINGS
        85,  // SET_WIFI_SEIZE_ENABLE
        112, // SET_WIFI_CONNECTION_INFO
        118, // SET_ACCESS_CAMERA_FILE_STATE
    )

    fun isFactory(code: Int): Boolean = code >= FACTORY_COMMAND_BEGIN

    fun isSweepable(code: Int): Boolean = code !in NEVER_SWEEP && !isFactory(code)

    /** Nome noto di un codice, se l'estrazione lo descrive. */
    fun nameOf(code: Int): String? = NAMES[code]

    fun describe(code: Int): String = when (code) {
        RESPONSE_OK -> "RISPOSTA_OK"
        RESPONSE_ERROR -> "RISPOSTA_ERRORE"
        else -> NAMES[code] ?: "SCONOSCIUTO_$code"
    }

    /**
     * I 164 codici che l'estrazione pubblica descrive. L'app Insta360 del 2026 ne nomina 459:
     * un codice assente da questa mappa non è necessariamente inesistente, è solo senza nome.
     */
    val NAMES: Map<Int, String> = mapOf(
        0 to "PHONE_COMMAND_BEGIN",
        1 to "PHONE_COMMAND_START_LIVE_STREAM",
        2 to "PHONE_COMMAND_STOP_LIVE_STREAM",
        3 to "PHONE_COMMAND_TAKE_PICTURE",
        4 to "PHONE_COMMAND_START_CAPTURE",
        5 to "PHONE_COMMAND_STOP_CAPTURE",
        6 to "PHONE_COMMAND_CANCEL_CAPTURE",
        7 to "PHONE_COMMAND_SET_OPTIONS",
        8 to "PHONE_COMMAND_GET_OPTIONS",
        9 to "PHONE_COMMAND_SET_PHOTOGRAPHY_OPTIONS",
        10 to "PHONE_COMMAND_GET_PHOTOGRAPHY_OPTIONS",
        11 to "PHONE_COMMAND_GET_FILE_EXTRA",
        12 to "PHONE_COMMAND_DELETE_FILES",
        13 to "PHONE_COMMAND_GET_FILE_LIST",
        14 to "PHONE_COMMAND_TAKE_PICTURE_WITHOUT_STORING",
        15 to "PHONE_COMMAND_GET_CURRENT_CAPTURE_STATUS",
        16 to "PHONE_COMMAND_SET_FILE_EXTRA",
        17 to "PHONE_COMMAND_GET_TIMELAPSE_OPTIONS",
        18 to "PHONE_COMMAND_SET_TIMELAPSE_OPTIONS",
        19 to "PHONE_COMMAND_GET_GYRO",
        22 to "PHONE_COMMAND_START_TIMELAPSE",
        23 to "PHONE_COMMAND_STOP_TIMELAPSE",
        24 to "PHONE_COMMAND_ERASE_SD_CARD",
        25 to "PHONE_COMMAND_CALIBRATE_GYRO",
        26 to "PHONE_COMMAND_SCAN_BT_PERIPHERAL",
        27 to "PHONE_COMMAND_CONNECT_TO_BT_PERIPHERAL",
        28 to "PHONE_COMMAND_DISCONNECT_BT_PERIPHERAL",
        29 to "PHONE_COMMAND_GET_CONNECTED_BT_PERIPHERALS",
        30 to "PHONE_COMMAND_GET_MINI_THUMBNAIL",
        31 to "PHONE_COMMAND_TEST_SD_CARD_SPEED",
        32 to "PHONE_COMMAND_REBOOT_CAMERA",
        33 to "PHONE_COMMAND_OPEN_CAMERA_WIFI",
        34 to "PHONE_COMMAND_CLOSE_CAMERA_WIFI",
        35 to "PHONE_COMMAND_OPEN_IPERF",
        36 to "PHONE_COMMAND_CLOSE_IPERF",
        37 to "PHONE_COMMAND_GET_IPERF_AVERAGE",
        38 to "PHONE_COMMAND_GET_FILEINFO_LIST",
        39 to "PHONE_COMMAND_CHECK_AUTHORIZATION",
        40 to "PHONE_COMMAND_CANCEL_AUTHORIZATION",
        41 to "PHONE_COMMAND_START_BULLETTIME_CAPTURE",
        42 to "PHONE_COMMAND_SET_SUBMODE_OPTIONS",
        43 to "PHONE_COMMAND_GET_SUBMODE_OPTIONS",
        48 to "PHONE_COMMAND_STOP_BULLETTIME_CAPTURE",
        49 to "PHONE_COMMAND_OPEN_OLED",
        50 to "PHONE_COMMAND_CLOSE_OLED",
        51 to "PHONE_COMMAND_START_HDR_CAPTURE",
        52 to "PHONE_COMMAND_STOP_HDR_CAPTURE",
        53 to "PHONE_COMMAND_UPLOAD_GPS",
        54 to "PHONE_COMMAND_SET_SYNC_CAPTURE_MODE",
        55 to "PHONE_COMMAND_GET_SYNC_CAPTURE_MODE",
        56 to "PHONE_COMMAND_SET_STANDBY_MODE",
        57 to "PHONE_COMMAND_RESTORE_FACTORY_SETTINGS",
        58 to "PHONE_COMMAND_SET_TEMPORARY_OPTIONS_SWITCH",
        59 to "PHONE_COMMAND_GET_TEMPORARY_OPTIONS_SWITCH",
        60 to "PHONE_COMMAND_SET_KEY_TIME_POINT",
        61 to "PHONE_COMMAND_START_TIMESHIFT_CAPTURE",
        62 to "PHONE_COMMAND_STOP_TIMESHIFT_CAPTURE",
        63 to "PHONE_COMMAND_SET_FLOWSTATE_ENABLE",
        64 to "PHONE_COMMAND_GET_FLOWSTATE_ENABLE",
        65 to "PHONE_COMMAND_SET_ACTIVE_SENSOR",
        66 to "PHONE_COMMAND_GET_ACTIVE_SENSOR",
        67 to "PHONE_COMMAND_SET_MULTI_PHOTOGRAPHY_OPTIONS",
        68 to "PHONE_COMMAND_GET_MULTI_PHOTOGRAPHY_OPTIONS",
        71 to "PHONE_COMMAND_GET_RECORDING_FILE",
        83 to "PHONE_COMMAND_PREPARE_GET_FILE_PACKAGE",
        84 to "PHONE_COMMAND_GET_FILE_PACKAGE_FINISH",
        85 to "PHONE_COMMAND_SET_WIFI_SEIZE_ENABLE",
        86 to "PHONE_COMMAND_REQUEST_AUTHORIZATION",
        87 to "PHONE_COMMAND_CANCEL_REQUEST_AUTHORIZATION",
        103 to "PHONE_COMMAND_SET_BUTTON_PRESS_PARAM",
        104 to "PHONE_COMMAND_GET_BUTTON_PRESS_PARAM",
        105 to "PHONE_COMMAND_IFRAME_REQUEST",
        112 to "PHONE_COMMAND_SET_WIFI_CONNECTION_INFO",
        113 to "PHONE_COMMAND_GET_WIFI_CONNECTION_INFO",
        118 to "PHONE_COMMAND_SET_ACCESS_CAMERA_FILE_STATE",
        120 to "PHONE_COMMAND_SET_APPID",
        151 to "PHONE_COMMAND_PREPARE_GET_FILE_SYNC_PACKAGE",
        152 to "PHONE_COMMAND_GET_FILE_PACKAGE_SYNC_FINISH",
        4096 to "PHONE_REQUEST_BEGIN",
        8192 to "CAMERA_NOTIFICATION_BEGIN",
        8193 to "CAMERA_NOTIFICATION_FIRMWARE_UPGRADE_COMPLETE",
        8194 to "CAMERA_NOTIFICATION_CAPTURE_AUTO_SPLIT",
        8195 to "CAMERA_NOTIFICATION_BATTERY_UPDATE",
        8196 to "CAMERA_NOTIFICATION_BATTERY_LOW",
        8197 to "CAMERA_NOTIFICATION_SHUTDOWN",
        8198 to "CAMERA_NOTIFICATION_STORAGE_UPDATE",
        8199 to "CAMERA_NOTIFICATION_STORAGE_FULL",
        8200 to "CAMERA_NOTIFICATION_KEY_PRESSED",
        8201 to "CAMERA_NOTIFICATION_CAPTURE_STOPPED",
        8202 to "CAMERA_NOTIFICATION_TAKE_PICTURE_STATE_UPDATE",
        8203 to "CAMERA_NOTIFICATION_DELETE_FILES_PROGRESS",
        8204 to "CAMERA_NOTIFICATION_PHONE_INSERT",
        8205 to "CAMERA_NOTIFICATION_BT_DISCOVER_PERIPHERAL",
        8206 to "CAMERA_NOTIFICATION_BT_CONNECTED_TO_PERIPHERAL",
        8207 to "CAMERA_NOTIFICATION_BT_DISCONNECTED_PERIPHERAL",
        8208 to "CAMERA_NOTIFICATION_CURRENT_CAPTURE_STATUS",
        8209 to "CAMERA_NOTIFICATION_AUTHORIZATION_RESULT",
        8210 to "CAMERA_NOTIFICATION_TIMELAPSE_STATUS_UPDATE",
        8211 to "CAMERA_NOTIFICATION_SYNC_CAPTURE_MODE_UPDATE",
        8212 to "CAMERA_NOTIFICATION_SYNC_CAPTURE_BUTTON_TRIGGER",
        8213 to "CAMERA_NOTIFICATION_BT_REMOTE_VER_UPDATED",
        8214 to "CAMERA_NOTIFICATION_CAM_TEMPERATURE_VALUE",
        8215 to "CAMERA_NOTIFICATION_CAM_WIFI_START",
        8216 to "CAMERA_NOTIFICATION_CAM_BT_MSG_ANALYZE_FAILED",
        8217 to "CAMERA_NOTIFICATION_CHARGE_BOX_BATTERY_UPDATE",
        8219 to "CAMERA_NOTIFICATION_LIVEVIEW_BEGIN_ROTATE",
        8220 to "CAMERA_NOTIFICATION_EXPOSURE_UPDATE",
        8222 to "CAMERA_NOTIFICATION_CHARGE_BOX_CONNECT_STATUS",
        8232 to "CAMERA_NOTIFICATION_WIFI_CONNECTION_RESULT",
        8234 to "CAMERA_NOTIFICATION_UPDATE_LIVE_STREAM_PARAMS",
        8238 to "CAMERA_NOTIFICATION_FIRMWARE_UPGRADE_STATUS_TOAPP",
        8248 to "CAMERA_NOTIFICATION_DATA_EXPORT_STATUS",
        8249 to "CAMERA_NOTIFICATION_WIFI_SCAN_LIST_CHANGED",
        8250 to "CAMERA_NOTIFICATION_DETECTED_FACE",
        12288 to "FACTORY_COMMAND_BEGIN",
        12289 to "FACTORY_COMMAND_OLED_TEST",
        12290 to "FACTORY_COMMAND_LED_TEST",
        12291 to "FACTORY_COMMAND_SPEAKER_TEST",
        12292 to "FACTORY_COMMAND_OLED_ROW_EVEN",
        12293 to "FACTORY_COMMAND_OLED_ROW_UNEVEN",
        12294 to "FACTORY_COMMAND_OLED_LINE_EVEN",
        12295 to "FACTORY_COMMAND_OLED_LINE_UNEVEN",
        12296 to "FACTORY_COMMAND_WIFI_STATUS_TEST",
        12297 to "FACTORY_COMMAND_BLUETOOTH_STATUS_TEST",
        12298 to "FACTORY_COMMAND_MOTOR_TEST",
        12299 to "FACTORY_COMMAND_WHITE_BALANCE_TEST",
        12300 to "FACTORY_COMMAND_GYROSCOPE_TEST",
        12301 to "FACTORY_COMMAND_LED_TEST_STOP",
        12302 to "FACTORY_COMMAND_MOTOR_TEST_STOP",
        12303 to "FACTORY_COMMAND_WHITE_BALANCE_STATUS_TEST",
        12304 to "FACTORY_COMMAND_FW_AGEING_TEST",
        12305 to "FACTORY_COMMAND_BUTTON_STATUS_TEST",
        12306 to "FACTORY_COMMAND_TP_JIUGONGGE_TEST",
        12307 to "FACTORY_COMMAND_LCD_COLOR_TEST",
        12308 to "FACTORY_COMMAND_USB_SPEED_TEST",
        12309 to "FACTORY_COMMAND_USB_LCD_TP_CLOSE_TEST",
        12310 to "FACTORY_COMMAND_VIGNETTE_DATA_SAVE",
        12311 to "FACTORY_COMMAND_BLC_DATA_SAVE",
        12312 to "FACTORY_COMMAND_BPC_DATA_SAVE",
        12313 to "FACTORY_COMMAND_PLAY_AUDIO",
        12320 to "FACTORY_COMMAND_STOP_AUDIO",
        12321 to "FACTORY_COMMAND_RECORD_AUDIO_START",
        12322 to "FACTORY_COMMAND_RECORD_AUDIO_STOP",
        12323 to "FACTORY_COMMAND_DEVLINK_TEST",
        12324 to "FACTORY_COMMAND_GET_AGEINGTEST_STATUS",
        12325 to "FACTORY_COMMAND_LCD_COLOR_TEST_STOP",
        12326 to "FACTORY_COMMAND_LCD_COLOR_TEST_COLOR_SET",
        12327 to "FACTORY_COMMAND_BUTTON_HANDLER_REFRESH",
        12328 to "FACTORY_COMMAND_OLED_CORRECTION_EVEN",
        12329 to "FACTORY_COMMAND_OLED_CORRECTION_UNEVEN",
        12330 to "FACTORY_COMMAND_OLED_CORRECTION_SET_OFFSET_TEST",
        12331 to "FACTORY_COMMAND_OLED_CORRECTION_GET_OFFSET_TEST",
        12332 to "FACTORY_COMMAND_CHARGINGBOX",
        12333 to "FACTORY_COMMAND_CHARGINGBOX_BUTTON_TEST",
        12334 to "FACTORY_COMMAND_CHARGINGBOX_HALL_TEST",
        12335 to "FACTORY_COMMAND_SCRIPT_JSON_UPLOAD",
        12336 to "FACTORY_COMMAND_SCRIPT_CMD_UPLOAD",
        12337 to "FACTORY_COMMAND_SCRIPT_REFRESH",
        12338 to "FACTORY_COMMAND_SCRIPT_CMD_RUN",
        12339 to "FACTORY_COMMAND_SET_AAA_FACTORYMODE",
        12340 to "FACTORY_COMMAND_SET_AAA_NORMALMODE",
        12341 to "FACTORY_COMMAND_GET_WHITEBLANCE_STATUS",
        12342 to "FACTORY_COMMAND_GET_SFR_STATUS",
        12343 to "FACTORY_COMMAND_GET_SFR_RESULT",
    )
}
