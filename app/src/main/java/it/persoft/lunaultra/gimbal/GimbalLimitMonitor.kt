package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.data.GimbalCalibrationSample
import it.persoft.lunaultra.protocol.LunaProtocolCodes
import it.persoft.lunaultra.protocol.ProtoField
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

    /**
     * L'ultimo payload 8302 arrivato, campo per campo.
     *
     * Di questa notifica conosciamo due campi su nove: i due flag che commutano sui finecorsa.
     * Gli altri sette non li ha mai letti nessuno, e se la camera dice dove si trova — che è
     * l'unico modo in cui l'app ufficiale possa unire dodici foto senza sbagliare gli angoli —
     * la posizione sta lì dentro. Tenere l'ultimo payload costa un riferimento e permette di
     * fotografarlo nei momenti in cui sappiamo *con certezza* dove il gimbal è: i finecorsa.
     *
     * Due estremi noti e i valori grezzi corrispondenti bastano a ricavare sia quale campo è
     * l'angolo sia in che unità è espresso.
     */
    @Volatile
    var lastPtzFields: List<ProtoField.VarInt> = emptyList()
        private set

    init {
        scope.launch {
            notifications.collect { frame ->
                val signal = GimbalLimitSignal.from(frame) ?: return@collect
                lastPtzFields = ProtoReader(frame.payload).fields().filterIsInstance<ProtoField.VarInt>()
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
