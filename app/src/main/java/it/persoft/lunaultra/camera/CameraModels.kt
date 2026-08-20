package it.persoft.lunaultra.camera

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    HANDSHAKE,
    CONNECTED,
    ERROR,
    ;

    val isUsable: Boolean get() = this == CONNECTED
}

/** Stato base della camera. I campi sono nullable: ciò che non si riesce a leggere resta ignoto. */
data class CameraStatus(
    val batteryPercent: Int? = null,
    val recording: Boolean? = null,
    val captureMode: String? = null,
    val captureSeconds: Int? = null,
    val freeSpaceBytes: Long? = null,
    val totalSpaceBytes: Long? = null,
    val model: String? = null,
    val serial: String? = null,
    val firmware: String? = null,
    val lastUpdateMs: Long = 0L,
    val rawDump: String? = null,
) {
    val hasData: Boolean
        get() = batteryPercent != null || recording != null || captureMode != null || model != null

    /** Fonde un aggiornamento parziale (es. una notifica di sola batteria) su quello corrente. */
    fun mergedWith(update: CameraStatus): CameraStatus = CameraStatus(
        batteryPercent = update.batteryPercent ?: batteryPercent,
        recording = update.recording ?: recording,
        captureMode = update.captureMode ?: captureMode,
        captureSeconds = update.captureSeconds ?: captureSeconds,
        freeSpaceBytes = update.freeSpaceBytes ?: freeSpaceBytes,
        totalSpaceBytes = update.totalSpaceBytes ?: totalSpaceBytes,
        model = update.model ?: model,
        serial = update.serial ?: serial,
        firmware = update.firmware ?: firmware,
        lastUpdateMs = maxOf(update.lastUpdateMs, lastUpdateMs),
        rawDump = update.rawDump ?: rawDump,
    )
}

/** Posizione del gimbal in gradi. */
data class PtzState(
    val pan: Float = 0f,
    val tilt: Float = 0f,
    val fromCamera: Boolean = false,
    val lastUpdateMs: Long = 0L,
) {
    companion object {
        val UNKNOWN = PtzState()
    }
}
