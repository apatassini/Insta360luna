package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.data.GimbalCalibrationSample
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoReader
import it.persoft.lunaultra.protocol.Ucd2Frame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

data class GimbalLimitSignal(
    val pitchLimit: Boolean,
    val yawLimit: Boolean,
) {
    companion object {
        /** `GimbalStatus`: campo 2 = pitch_limit, campo 3 = yaw_limit. */
        fun from(frame: Ucd2Frame): GimbalLimitSignal? {
            if (frame.code != LunaProtocolCodes.NOTIFICATION_PTZ_STATE_OBSERVED) return null
            val reader = ProtoReader(frame.payload)
            return GimbalLimitSignal(
                pitchLimit = reader.intOrNull(2) == 1,
                yawLimit = reader.intOrNull(3) == 1,
            )
        }
    }
}

/** Conserva il fronte di fine corsa anche quando il frame successivo rimette il flag a zero. */
class GimbalLimitMonitor(
    notifications: SharedFlow<Ucd2Frame>,
    scope: CoroutineScope,
) {
    private val lastPitchLimitNanos = AtomicLong(0L)
    private val lastYawLimitNanos = AtomicLong(0L)

    init {
        scope.launch {
            notifications.collect { frame ->
                val signal = GimbalLimitSignal.from(frame) ?: return@collect
                val now = System.nanoTime()
                if (signal.pitchLimit) lastPitchLimitNanos.set(now)
                if (signal.yawLimit) lastYawLimitNanos.set(now)
            }
        }
    }

    fun mark(): Long = System.nanoTime()

    fun reached(axis: String, sinceNanos: Long): Boolean =
        if (axis == GimbalCalibrationSample.AXIS_PAN) {
            lastYawLimitNanos.get() >= sinceNanos
        } else {
            lastPitchLimitNanos.get() >= sinceNanos
        }
}
