package it.persoft.lunaultra.media

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import it.persoft.lunaultra.data.JsonFileStore
import it.persoft.lunaultra.net.EventLog
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/** Un punto del diario: dove stava il telefono in quel momento. */
@Serializable
data class PositionFix(
    val timeMs: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Double? = null,
)

@Serializable
data class PositionLog(
    val fixes: List<PositionFix> = emptyList(),
)

/**
 * Il diario delle posizioni del telefono, per mettere il GPS nelle foto.
 *
 * La camera non sa dove sta; il telefono sì, e durante gli scatti sta in mano a chi scatta.
 * Finché si è connessi alla camera il diario annota la posizione ogni tanto; quando una foto
 * viene copiata sul telefono, le si scrive negli EXIF la posizione annotata più vicina alla
 * sua ora di scatto. Così anche una foto scaricata la sera, a casa, riceve le coordinate del
 * posto dov'è stata scattata — non quelle del divano.
 *
 * Il permesso di posizione è lo stesso già chiesto al primo avvio per il Wi-Fi: senza,
 * il diario resta vuoto e le foto restano senza coordinate, il resto funziona uguale.
 */
class LocationDiary(
    private val context: Context,
    private val store: JsonFileStore<PositionLog>,
    private val log: EventLog,
) {

    @Volatile
    private var announcedMissingPermission = false

    /** Annota la posizione attuale, se il permesso c'è e un fix si trova. */
    suspend fun sample() {
        if (!hasPermission()) {
            if (!announcedMissingPermission) {
                announcedMissingPermission = true
                log.warn(
                    "Posizione senza permesso: le foto resteranno senza GPS",
                    "Concedi la posizione all'app nelle impostazioni di Android per avere le coordinate negli EXIF.",
                )
            }
            return
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val known = lastKnown(manager)
        val fresh = if (known == null || System.currentTimeMillis() - known.time > FRESH_ENOUGH_MS) {
            requestFresh(manager) ?: known
        } else {
            known
        }
        if (fresh != null) record(fresh)
    }

    /** Il punto del diario più vicino a quell'ora, se non è troppo lontano per fidarsi. */
    fun nearest(timeMs: Long, maxGapMs: Long = MAX_GAP_MS): PositionFix? {
        val reference = if (timeMs > 0) timeMs else System.currentTimeMillis()
        return store.state.value.fixes
            .minByOrNull { abs(it.timeMs - reference) }
            ?.takeIf { abs(it.timeMs - reference) <= maxGapMs }
    }

    /**
     * Scrive nelle intestazioni EXIF la posizione annotata più vicina all'ora dello scatto.
     * Restituisce true se c'era qualcosa da scrivere; `saveAttributes` resta al chiamante.
     */
    fun stamp(exif: ExifInterface, takenAtMs: Long): Boolean {
        val fix = nearest(takenAtMs) ?: return false
        exif.setLatLong(fix.latitude, fix.longitude)
        fix.altitude?.let { exif.setAltitude(it) }
        val utc = TimeZone.getTimeZone("UTC")
        exif.setAttribute(
            ExifInterface.TAG_GPS_TIMESTAMP,
            SimpleDateFormat("HH:mm:ss", Locale.US).apply { timeZone = utc }.format(Date(fix.timeMs)),
        )
        exif.setAttribute(
            ExifInterface.TAG_GPS_DATESTAMP,
            SimpleDateFormat("yyyy:MM:dd", Locale.US).apply { timeZone = utc }.format(Date(fix.timeMs)),
        )
        return true
    }

    /** Comodità per i file già sul disco: apre, scrive, salva. */
    fun stampFile(file: java.io.File, takenAtMs: Long): Boolean = runCatching {
        val exif = ExifInterface(file)
        if (!stamp(exif, takenAtMs)) return false
        exif.saveAttributes()
        true
    }.getOrElse {
        log.warn("GPS non scritto in ${file.name}: ${it.message}")
        false
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun lastKnown(manager: LocationManager): Location? =
        PROVIDERS.mapNotNull { provider ->
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.time }

    /**
     * Chiede un fix nuovo e aspetta al massimo [FRESH_WAIT_MS]: sotto un tetto o con il GPS
     * lento è normale non ricevere niente, e allora si tiene l'ultimo fix noto.
     */
    @SuppressLint("MissingPermission")
    private suspend fun requestFresh(manager: LocationManager): Location? =
        withTimeoutOrNull(FRESH_WAIT_MS) {
            val provider = PROVIDERS.firstOrNull { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
                ?: return@withTimeoutOrNull null
            suspendCancellableCoroutine<Location?> { continuation ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val signal = CancellationSignal()
                    continuation.invokeOnCancellation { signal.cancel() }
                    manager.getCurrentLocation(
                        provider,
                        signal,
                        ContextCompat.getMainExecutor(context),
                    ) { location ->
                        if (continuation.isActive) continuation.resumeWith(Result.success(location))
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(
                        provider,
                        { location -> if (continuation.isActive) continuation.resumeWith(Result.success(location)) },
                        context.mainLooper,
                    )
                }
            }
        }

    /**
     * Un punto nuovo entra solo se aggiunge informazione: passato del tempo o fatta strada.
     * Il diario resta piccolo e copre comunque ogni scatto con un punto a pochi minuti.
     */
    private fun record(location: Location) {
        val fix = PositionFix(
            timeMs = location.time.takeIf { it > 0 } ?: System.currentTimeMillis(),
            latitude = location.latitude,
            longitude = location.longitude,
            altitude = if (location.hasAltitude()) location.altitude else null,
        )
        store.update { diary ->
            val last = diary.fixes.lastOrNull()
            val results = FloatArray(1)
            val moved = last?.let {
                Location.distanceBetween(it.latitude, it.longitude, fix.latitude, fix.longitude, results)
                results[0] > MIN_STEP_METERS
            } ?: true
            if (last != null && !moved && fix.timeMs - last.timeMs < MIN_STEP_MS) {
                diary
            } else {
                diary.copy(fixes = (diary.fixes + fix).takeLast(MAX_FIXES))
            }
        }
    }

    private companion object {
        val PROVIDERS = listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        )

        /** Un fix più giovane di così si usa senza chiedere di meglio. */
        const val FRESH_ENOUGH_MS = 10 * 60_000L

        /** Quanto si aspetta un fix nuovo prima di accontentarsi. */
        const val FRESH_WAIT_MS = 20_000L

        /** Una foto trova le sue coordinate solo entro questa distanza di tempo. */
        const val MAX_GAP_MS = 3 * 60 * 60_000L

        const val MIN_STEP_MS = 3 * 60_000L
        const val MIN_STEP_METERS = 30f
        const val MAX_FIXES = 5_000
    }
}
