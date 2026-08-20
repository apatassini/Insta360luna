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

/** Stato base della camera. I campi sono nullable: ciò che non si riesce a decodificare resta ignoto. */
data class CameraStatus(
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    val recording: Boolean? = null,
    val captureMode: String? = null,
    val model: String? = null,
    val firmware: String? = null,
    val lastUpdateMs: Long = 0L,
    val rawDump: String? = null,
) {
    val hasData: Boolean
        get() = batteryPercent != null || recording != null || captureMode != null || model != null
}

/** Posizione del gimbal in gradi. */
data class PtzState(
    val pan: Float = 0f,
    val tilt: Float = 0f,
    val roll: Float = 0f,
    val fromCamera: Boolean = false,
    val lastUpdateMs: Long = 0L,
) {
    companion object {
        val UNKNOWN = PtzState()
    }
}
