package it.persoft.lunaultra.gimbal

import it.persoft.lunaultra.camera.CameraMode
import it.persoft.lunaultra.camera.LunaCommands
import it.persoft.lunaultra.media.MediaItem
import it.persoft.lunaultra.media.MediaRepository
import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.preview.PreviewController
import it.persoft.lunaultra.stitch.InstaTrailer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Risultato diretto dei due movimenti opposti registrati nel proxy LRV. */
data class MisuraCurvaGiroscopio(
    val gradiAndata: Float,
    val gradiRitorno: Float,
    val durataComandoSecondi: Float,
) {
    val gradiSecondo: Float
        get() = (gradiAndata + gradiRitorno) / 2f / durataComandoSecondi

    val scartoChiusuraGradi: Float
        get() = abs(gradiAndata - gradiRitorno)
}

/**
 * Misura quanto ruota davvero il gimbal registrando la traccia inerziale della Luna.
 *
 * Ogni punto della curva è una breve registrazione: riposo per il bias, impulso in un verso,
 * pausa, impulso opposto e altro riposo. Si scarica soltanto il proxy LRV, che contiene la
 * stessa traccia a 1 kHz del video principale. Dopo una lettura valida vengono eliminati dalla
 * camera solo il video temporaneo appena creato e il suo compagno LRV.
 */
class GimbalGyroMeter(
    private val commands: LunaCommands,
    private val preview: PreviewController,
    private val media: MediaRepository,
    private val gimbal: GimbalController,
    private val log: EventLog,
) {

    suspend fun misura(
        panAxis: Boolean,
        intensityPercent: Int,
        durationMs: Long,
    ): Result<MisuraCurvaGiroscopio> {
        return try {
            Result.success(eseguiMisura(panAxis, intensityPercent, durationMs))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    suspend fun ripristinaModalita(mode: CameraMode) {
        commands.applyMode(mode).onFailure {
            log.warn("Ripristino modalità ${mode.label} non riuscito: ${it.message}")
        }
    }

    private suspend fun eseguiMisura(
        panAxis: Boolean,
        intensityPercent: Int,
        durationMs: Long,
    ): MisuraCurvaGiroscopio {
        require(intensityPercent in 1..100) { "Intensità non valida: $intensityPercent%" }
        require(durationMs in 500L..5_000L) { "Durata misura non valida: $durationMs ms" }
        if (!preview.ensureRunningForCapture()) {
            throw IllegalStateException("Il live stream non è partito: senza anteprima la Luna non salva il video")
        }
        // Anche se l'anteprima è già visibile, si invia il comando esplicito: con il solo
        // trasporto MJPEG la camera può sembrare attiva ma rifiutare START_CAPTURE con 8201/2.
        commands.startLiveStream().getOrThrow()
        delay(ATTESA_STREAM_MS)

        val percorsiPrima = media.list().getOrThrow().map(MediaItem::path).toSet()
        commands.applyMode(CameraMode.VIDEO).getOrThrow()
        delay(ATTESA_MODALITA_MS)

        var registrazione = false
        try {
            commands.startCapture().getOrThrow()
            registrazione = true
            delay(RIPOSO_INIZIALE_MS)

            val comando = intensityPercent / 100f
            gimbal.calibrationPulse(
                panPercent = if (panAxis) comando else 0f,
                tiltPercent = if (panAxis) 0f else comando,
                durationMs = durationMs,
            ).getOrThrow()
            delay(PAUSA_FRA_MOVIMENTI_MS)
            gimbal.calibrationPulse(
                panPercent = if (panAxis) -comando else 0f,
                tiltPercent = if (panAxis) 0f else -comando,
                durationMs = durationMs,
            ).getOrThrow()
            delay(RIPOSO_FINALE_MS)

            commands.stopCapture().getOrThrow()
            registrazione = false
            if (!commands.awaitCaptureIdle()) {
                throw IllegalStateException("La camera non ha chiuso la registrazione di taratura")
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { gimbal.stop() }
                if (registrazione) {
                    runCatching { commands.stopCapture() }
                    commands.awaitCaptureIdle()
                }
            }
        }

        var nuovoVideo: MediaItem? = null
        for (tentativo in 0 until TENTATIVI_FILE) {
            nuovoVideo = media.list().getOrThrow().firstOrNull {
                it.isVideo && it.path !in percorsiPrima && it.proxyPath != null
            }
            if (nuovoVideo != null) break
            delay(ATTESA_FILE_MS)
        }
        val video = nuovoVideo ?: throw IllegalStateException(
            "La registrazione è finita ma il nuovo LRV non compare nella libreria",
        )
        val file = media.cache(video, preferProxy = true).getOrThrow()
        val traccia = InstaTrailer.readGyroTrack(file)
            ?: throw IllegalStateException("Il proxy ${video.proxyPath} non contiene una traccia giroscopica valida")
        val movimenti = traccia.trovaDueMovimenti(RIPOSO_BIAS_SECONDI)
        val risultato = MisuraCurvaGiroscopio(
            gradiAndata = movimenti.andata.rotazione.angoloGradi.toFloat(),
            gradiRitorno = movimenti.ritorno.rotazione.angoloGradi.toFloat(),
            durataComandoSecondi = durationMs / 1_000f,
        )

        // Il file è nato esclusivamente per questa misura. Si elimina solo dopo averlo letto
        // correttamente; in caso di errore resta sulla camera per poterlo diagnosticare.
        commands.deleteFiles(listOf(video.path)).onSuccess { rifiutati ->
            if (rifiutati.isNotEmpty()) {
                log.warn("Video temporaneo non eliminato: ${rifiutati.joinToString()}")
            }
        }.onFailure {
            log.warn("Eliminazione del video temporaneo ${video.name} non riuscita: ${it.message}")
        }
        file.delete()
        log.info(
            "MISURA GIROSCOPICA · ${if (panAxis) "ORIZZONTALE" else "VERTICALE"} $intensityPercent%",
            "Andata %.2f° · ritorno %.2f° · media %.2f °/s · scarto %.2f° · soglia %.2f °/s".format(
                risultato.gradiAndata,
                risultato.gradiRitorno,
                risultato.gradiSecondo,
                risultato.scartoChiusuraGradi,
                movimenti.sogliaGradiSecondo,
            ),
        )
        return risultato
    }

    private companion object {
        const val ATTESA_STREAM_MS = 800L
        const val ATTESA_MODALITA_MS = 700L
        const val RIPOSO_INIZIALE_MS = 1_000L
        const val PAUSA_FRA_MOVIMENTI_MS = 700L
        const val RIPOSO_FINALE_MS = 1_000L
        const val RIPOSO_BIAS_SECONDI = 0.7
        /**
         * Quanto si aspetta che la camera faccia comparire la registrazione appena chiusa.
         *
         * Venti tentativi da settecento millisecondi facevano quattordici secondi, e bastavano
         * finche' i clip duravano sei secondi. Allungando gli impulsi a cinque secondi per
         * misurare l'1%, il clip e' arrivato a tredici — e la camera ci ha messo piu' di
         * quattordici secondi a indicizzarlo. Ogni misura falliva per il file, non per il
         * gimbal, e il referto lo chiamava «non muove»: la diagnosi sbagliata peggiore, perche'
         * manda a cercare nel motore un guasto che sta nell'attesa.
         *
         * Quarantacinque secondi: piu' del triplo del clip piu' lungo che questa misura sappia
         * produrre.
         */
        const val TENTATIVI_FILE = 45
        const val ATTESA_FILE_MS = 1_000L
    }
}
