package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.camera.PtzState
import it.persoft.lunaultra.data.AppSettings
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.net.EventLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.sign

/**
 * Pilota il gimbal e mantiene una stima della posizione corrente.
 *
 * Sulla Luna Ultra la posizione reale non è leggibile: la notifica PTZ è identificata solo per
 * numero, non per contenuto. La stima usa quindi dead reckoning, integrando le velocità
 * comandate — sufficiente a memorizzare i waypoint e a ripercorrerli in modo ripetibile.
 * Se un giorno la notifica verrà decodificata, [onCameraPosition] la sostituisce al dato stimato.
 */
class GimbalController(
    private val commands: LunaCommands,
    private val settings: StateFlow<AppSettings>,
    private val calibration: StateFlow<GimbalCalibrationProfile>,
    private val log: EventLog,
    private val scope: CoroutineScope,
) {

    private val _position = MutableStateFlow(PtzState())
    val position: StateFlow<PtzState> = _position

    private var jogJob: Job? = null

    /**
     * Direzione richiesta dal comando manuale, letta a ogni tick dal ciclo di invio.
     *
     * È volatile perché la scrive il thread della UI (il dito sulla levetta) e la legge la
     * coroutine che parla con la camera. Tenerla fuori dal ciclo è ciò che permette di cambiare
     * direzione senza interromperlo: una levetta analogica cambia valore a ogni frazione di
     * secondo, e riavviare il ciclo ogni volta manderebbe alla camera raffiche di avvii.
     */
    @Volatile
    private var jogPan = 0f

    @Volatile
    private var jogTilt = 0f

    private val _moving = MutableStateFlow(false)
    val moving: StateFlow<Boolean> = _moving

    /** Ultimo vettore realmente consegnato alla camera, per integrare il tempo reale. */
    private var appliedPanPercent = 0f
    private var appliedTiltPercent = 0f
    private var appliedSinceNanos = 0L

    /** Accumulatori per velocità medie inferiori all'1%, non rappresentabili dal protocollo. */
    private var panDutyAccumulator = 0f
    private var tiltDutyAccumulator = 0f

    /** Aggiorna la stima con un dato reale letto dalla camera. */
    fun onCameraPosition(state: PtzState) {
        _position.value = state
        appliedSinceNanos = System.nanoTime()
    }

    /** Azzera/forza la posizione stimata (usato quando la camera non espone il PTZ). */
    fun setEstimated(pan: Float, tilt: Float) {
        integrateAppliedUntilNow()
        _position.value = _position.value.copy(
            pan = pan,
            tilt = tilt,
            fromCamera = false,
            lastUpdateMs = System.currentTimeMillis(),
        )
        appliedSinceNanos = System.nanoTime()
    }

    /** Movimento manuale continuo: direzioni in [-1, 1]. Va fermato con [stop]. */
    fun startJog(panDirection: Float, tiltDirection: Float) = setJog(panDirection, tiltDirection)

    /**
     * Cambia la direzione del movimento manuale, avviando il ciclo di comandi se è fermo.
     *
     * Le direzioni sono in [-1, 1] su entrambi gli assi e valgono insieme: la levetta analogica
     * manda una diagonale, la croce direzionale un asse solo. Va fermato con [stop].
     */
    fun setJog(panDirection: Float, tiltDirection: Float) {
        jogPan = panDirection.coerceIn(-1f, 1f)
        jogTilt = tiltDirection.coerceIn(-1f, 1f)
        _moving.value = jogPan != 0f || jogTilt != 0f
        if (jogJob?.isActive != true) startJogLoop()
    }

    private fun startJogLoop() {
        jogJob?.cancel()
        jogJob = scope.launch {
            while (isActive) {
                // Velocità e cadenza si rileggono a ogni giro: il cursore della velocità deve
                // avere effetto mentre il gimbal si muove, non al movimento successivo.
                val cfg = settings.value.gimbal
                val rateHz = cfg.commandRateHz.coerceIn(1, 50)
                val periodMs = (1000L / rateHz).coerceAtLeast(20L)
                val speedFraction = cfg.manualSpeedPercent.coerceIn(1, 100) / 100f
                val panPercent = jogPan * speedFraction
                val tiltPercent = jogTilt * speedFraction
                sendVelocity(panPercent, tiltPercent)
                    .onFailure {
                        log.error("Movimento gimbal non riuscito: ${it.message}")
                        _moving.value = false
                        return@launch
                    }
                delay(periodMs)
            }
        }
    }

    /**
     * Stop del movimento manuale e stop di emergenza.
     *
     * Insta360Linker mantiene quattro vettori nulli dopo il rilascio: ripeterli evita che un
     * singolo pacchetto perso lasci il gimbal in movimento.
     */
    suspend fun stop() {
        jogPan = 0f
        jogTilt = 0f
        jogJob?.cancel()
        jogJob = null
        _moving.value = false
        integrateAppliedUntilNow()
        repeat(STOP_VECTOR_REPETITIONS) { index ->
            commands.gimbalStop().onFailure {
                log.warn("Stop gimbal non inviato: ${it.message}")
                return
            }
            if (index == 0) {
                appliedPanPercent = 0f
                appliedTiltPercent = 0f
                appliedSinceNanos = System.nanoTime()
            }
            if (index < STOP_VECTOR_REPETITIONS - 1) delay(STOP_VECTOR_INTERVAL_MS)
        }
    }

    /** Usa lo zero meccanico del firmware; non simula il ritorno integrando impulsi. */
    suspend fun recenter(): Result<Unit> {
        stop()
        val result = commands.gimbalBackCenter()
        if (result.isSuccess) {
            appliedPanPercent = 0f
            appliedTiltPercent = 0f
            appliedSinceNanos = System.nanoTime()
        }
        return result
    }

    /**
     * Porta il gimbal verso [targetPan]/[targetTilt] nell'arco di [stepSeconds], calcolando la
     * velocità necessaria a coprire l'errore residuo nel tempo del tick.
     *
     * Il movimento è solo a velocità: il comando di posizione assoluta
     * (`PHONE_COMMAND_SET_PTZ_OPTION`) esiste per nome ma non se ne conosce il numero, quindi
     * non c'è modo di inviarlo.
     */
    suspend fun driveTo(targetPan: Float, targetTilt: Float, stepSeconds: Float): Result<Unit> {
        integrateAppliedUntilNow()
        val current = _position.value
        val dt = stepSeconds.coerceAtLeast(0.02f)
        val panMaximum = maximumAngularSpeed(panAxis = true)
        val tiltMaximum = maximumAngularSpeed(panAxis = false)
        val panSpeed = clampSpeed((targetPan - current.pan) / dt, panMaximum)
        val tiltSpeed = clampSpeed((targetTilt - current.tilt) / dt, tiltMaximum)
        val panFraction = if (panMaximum > 0f) panSpeed / panMaximum else 0f
        val tiltFraction = if (tiltMaximum > 0f) tiltSpeed / tiltMaximum else 0f
        val panCommand = commandForRequestedFraction(panFraction, panAxis = true)
        val tiltCommand = commandForRequestedFraction(tiltFraction, panAxis = false)
        return sendVelocity(panCommand, tiltCommand)
    }

    /** Tempo minimo stimato per raggiungere un punto, considerando entrambi gli assi. */
    fun estimatedTravelSeconds(targetPan: Float, targetTilt: Float): Float {
        integrateAppliedUntilNow()
        val current = _position.value
        val panMaximum = maximumAngularSpeed(panAxis = true)
        val tiltMaximum = maximumAngularSpeed(panAxis = false)
        val panSeconds = if (panMaximum > 0f) {
            abs(targetPan - current.pan) / panMaximum
        } else 0f
        val tiltSeconds = if (tiltMaximum > 0f) {
            abs(targetTilt - current.tilt) / tiltMaximum
        } else 0f
        return max(panSeconds, tiltSeconds)
    }

    /**
     * Raggiunge una posizione senza il vecchio limite fisso di due/tre secondi.
     *
     * La durata cresce con la distanza: era il limite fisso a lasciare il punto iniziale
     * spostato verso destra quando il ritorno dal punto 2 richiedeva più di circa 60°.
     */
    suspend fun moveToPosition(
        targetPan: Float,
        targetTilt: Float,
        minimumSeconds: Float = 0f,
        tickHz: Int = POSITION_TICK_HZ,
    ): Result<Unit> {
        stop()
        val travel = max(minimumSeconds, estimatedTravelSeconds(targetPan, targetTilt))
        val duration = travel * POSITION_TIME_MARGIN + POSITION_SETTLE_MARGIN_SECONDS
        val rate = tickHz.coerceIn(1, 50)
        val steps = ceil(duration * rate).toInt().coerceIn(1, MAX_POSITION_STEPS)
        val stepSeconds = duration / steps
        repeat(steps) {
            val result = driveTo(targetPan, targetTilt, stepSeconds)
            if (result.isFailure) {
                stop()
                return result
            }
            delay((stepSeconds * 1000f).toLong().coerceAtLeast(20L))
        }
        stop()
        return Result.success(Unit)
    }

    /**
     * Spostamento fra fotografie: usa il 100% sull'asse ancora lontano e lo ferma appena entra
     * nella tolleranza. Riduce al minimo il tempo fra due scatti; video e timelapse continuano
     * invece a ricavare dalla durata la velocità più bassa sufficiente.
     */
    suspend fun moveToPositionAtMaximum(targetPan: Float, targetTilt: Float): Result<Unit> {
        stop()
        val cfg = settings.value.gimbal
        val periodMs = (1000L / cfg.commandRateHz.coerceIn(1, 50)).coerceAtLeast(20L)
        val panTolerance = (maximumAngularSpeed(true) * periodMs / 1000f).coerceAtLeast(0.35f)
        val tiltTolerance = (maximumAngularSpeed(false) * periodMs / 1000f).coerceAtLeast(0.35f)
        val timeoutMs = ((estimatedTravelSeconds(targetPan, targetTilt) * 1_600f) + 1_200f)
            .toLong().coerceAtLeast(1_500L)
        val startedAt = System.nanoTime()
        while ((System.nanoTime() - startedAt) / 1_000_000L < timeoutMs) {
            integrateAppliedUntilNow()
            val current = _position.value
            val panError = targetPan - current.pan
            val tiltError = targetTilt - current.tilt
            val panCommand = if (abs(panError) <= panTolerance) 0f else sign(panError)
            val tiltCommand = if (abs(tiltError) <= tiltTolerance) 0f else sign(tiltError)
            if (panCommand == 0f && tiltCommand == 0f) {
                stop()
                return Result.success(Unit)
            }
            val sent = sendVelocity(panCommand, tiltCommand)
            if (sent.isFailure) {
                stop()
                return sent
            }
            delay(periodMs)
        }
        stop()
        return Result.failure(IllegalStateException("Tempo massimo di spostamento fotografico superato"))
    }

    /**
     * Piccolo impulso a velocità controllata usato dal riallineamento fotografico.
     *
     * Non passa dalla velocità della levetta manuale: la correzione deve essere ripetibile e
     * abbastanza lenta da poter essere misurata nel fotogramma successivo. Ogni impulso parte
     * e termina con vettori nulli, evitando che una correzione resti attiva oltre il previsto.
     */
    suspend fun correctionPulse(
        panPercent: Float,
        tiltPercent: Float,
        durationMs: Long,
    ): Result<Unit> {
        return velocityPulse(panPercent, tiltPercent, durationMs, VISUAL_MAX_SPEED, 600L)
    }

    /** Impulso esteso 1..100% usato esclusivamente per costruire la curva di calibrazione. */
    suspend fun calibrationPulse(
        panPercent: Float,
        tiltPercent: Float,
        durationMs: Long,
    ): Result<Unit> = velocityPulse(panPercent, tiltPercent, durationMs, 1f, 5_000L)

    private suspend fun velocityPulse(
        panPercent: Float,
        tiltPercent: Float,
        durationMs: Long,
        maxCommand: Float,
        maxDurationMs: Long,
    ): Result<Unit> {
        stop()
        val duration = durationMs.coerceIn(60L, maxDurationMs)
        val periodMs = (1000L / settings.value.gimbal.commandRateHz.coerceIn(1, 50)).coerceAtLeast(20L)
        val startedAt = System.nanoTime()
        do {
            val sent = sendVelocity(
                panPercent.coerceIn(-maxCommand, maxCommand),
                tiltPercent.coerceIn(-maxCommand, maxCommand),
            )
            if (sent.isFailure) {
                stop()
                return sent
            }
            delay(periodMs)
        } while ((System.nanoTime() - startedAt) / 1_000_000L < duration)
        stop()
        return Result.success(Unit)
    }

    private suspend fun sendVelocity(panPercent: Float, tiltPercent: Float): Result<Unit> {
        integrateAppliedUntilNow()
        val result = commands.gimbalVelocity(panPercent, tiltPercent)
        if (result.isSuccess) {
            appliedPanPercent = panPercent
            appliedTiltPercent = tiltPercent
            appliedSinceNanos = System.nanoTime()
        }
        return result
    }

    private fun clampSpeed(requested: Float, max: Float): Float {
        if (max <= 0f) return 0f
        return if (abs(requested) > max) max * sign(requested) else requested
    }

    /** Preferisce la velocità in gradi/s misurata sulla corsa completa; usa la stima solo prima della calibrazione. */
    private fun maximumAngularSpeed(panAxis: Boolean): Float {
        val profile = calibration.value
        val measured = profile.maxAngularRate(panAxis)
        if (profile.isValid && measured > 0f) return measured
        val fallback = settings.value.gimbal
        return if (panAxis) fallback.maxPanSpeedDegPerSec else fallback.maxTiltSpeedDegPerSec
    }

    /**
     * Il comando camera è intero: 0%, 1%, 2%… Per richieste inferiori all'1% usa pulse-density
     * modulation fra 1% e stop, ottenendo sul lungo periodo la velocità media richiesta.
     */
    private fun commandForRequestedFraction(desiredFraction: Float, panAxis: Boolean): Float {
        if (desiredFraction == 0f) {
            if (panAxis) panDutyAccumulator = 0f else tiltDutyAccumulator = 0f
            return 0f
        }
        val profile = calibration.value
        if (!profile.isValid) return desiredFraction.coerceIn(-1f, 1f)
        val minimumMotion = abs(profile.motionFraction(MIN_PROTOCOL_COMMAND, panAxis))
        if (minimumMotion <= 0f || abs(desiredFraction) >= minimumMotion) {
            return profile.commandForMotionFraction(desiredFraction, panAxis)
        }
        val increment = (abs(desiredFraction) / minimumMotion).coerceIn(0f, 1f)
        val accumulated = (if (panAxis) panDutyAccumulator else tiltDutyAccumulator) + increment
        return if (accumulated >= 1f) {
            if (panAxis) panDutyAccumulator = accumulated - 1f else tiltDutyAccumulator = accumulated - 1f
            MIN_PROTOCOL_COMMAND * sign(desiredFraction)
        } else {
            if (panAxis) panDutyAccumulator = accumulated else tiltDutyAccumulator = accumulated
            0f
        }
    }

    /** Dead reckoning: integra la velocità comandata nella posizione stimata. */
    private fun integrate(panPercent: Float, tiltPercent: Float, dtSeconds: Float) {
        val profile = calibration.value
        val cfg = settings.value.gimbal
        val current = _position.value
        val panMotion = profile.motionFraction(panPercent, panAxis = true)
        val tiltMotion = profile.motionFraction(tiltPercent, panAxis = false)
        val panLimits = profile.panLimits.takeIf { profile.isValid }
        val tiltLimits = profile.tiltLimits.takeIf { profile.isValid }
        val pan = (current.pan + panMotion * maximumAngularSpeed(true) * dtSeconds)
            .coerceIn(panLimits?.minimumDeg ?: cfg.panMinDeg, panLimits?.maximumDeg ?: cfg.panMaxDeg)
        val tilt = (current.tilt + tiltMotion * maximumAngularSpeed(false) * dtSeconds)
            .coerceIn(tiltLimits?.minimumDeg ?: cfg.tiltMinDeg, tiltLimits?.maximumDeg ?: cfg.tiltMaxDeg)
        // Il flag fromCamera resta a false: verrà rialzato dal prossimo dato reale della camera.
        _position.value = current.copy(
            pan = pan,
            tilt = tilt,
            fromCamera = false,
            lastUpdateMs = System.currentTimeMillis(),
        )
    }

    /**
     * Integra quanto il comando precedente è rimasto davvero attivo, non il periodo teorico.
     * Latenza TCP e scheduler altrimenti si accumulavano e spostavano i waypoint.
     */
    private fun integrateAppliedUntilNow(nowNanos: Long = System.nanoTime()) {
        val since = appliedSinceNanos
        if (since == 0L) {
            appliedSinceNanos = nowNanos
            return
        }
        val elapsed = ((nowNanos - since) / 1_000_000_000.0).toFloat().coerceAtLeast(0f)
        if (elapsed > 0f && (appliedPanPercent != 0f || appliedTiltPercent != 0f)) {
            integrate(appliedPanPercent, appliedTiltPercent, elapsed)
        }
        appliedSinceNanos = nowNanos
    }

    companion object {
        const val STOP_VECTOR_REPETITIONS = 4
        const val STOP_VECTOR_INTERVAL_MS = 25L
        private const val POSITION_TICK_HZ = 10
        private const val POSITION_TIME_MARGIN = 1.15f
        private const val POSITION_SETTLE_MARGIN_SECONDS = 0.35f
        private const val MAX_POSITION_STEPS = 3_600
        private const val VISUAL_MAX_SPEED = 0.35f
        private const val MIN_PROTOCOL_COMMAND = 0.01f
    }
}
