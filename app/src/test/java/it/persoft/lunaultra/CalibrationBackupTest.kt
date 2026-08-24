package it.persoft.lunaultra

import it.persoft.lunaultra.data.GimbalAxisLimits
import it.persoft.lunaultra.data.GimbalCalibrationProfile
import it.persoft.lunaultra.data.GimbalResponsePoint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La calibrazione deve sopravvivere a una reinstallazione.
 *
 * Misurarla costa sette minuti di gimbal che va a sbattere nei fine corsa, ed è una misura
 * dell'hardware: la corsa degli assi e la curva dei comandi sono le stesse ieri e domani.
 * L'unica ragione per rifarla era averla persa, quindi il profilo si scrive su file e si
 * rilegge — e quello che rientra deve essere esattamente quello che era uscito, valido com'era.
 */
class CalibrationBackupTest {

    /** Lo stesso `Json` dello store dell'app: se cambia lì, questo test se ne accorge. */
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun profile(): GimbalCalibrationProfile = GimbalCalibrationProfile(
        calibratedAtMs = 1_756_000_000_000L,
        cameraModel = "Luna Ultra",
        firmware = "v1.0.288",
        responseOverheadMs = 120L,
        settleMs = 260L,
        validSamples = 42,
        totalSamples = 42,
        responsePoints = listOf(1, 5, 10, 20, 40, 60, 80, 90, 100).map { intensity ->
            GimbalResponsePoint(
                intensityPercent = intensity,
                panImagePixelsPerSecond = intensity * 4f,
                tiltImagePixelsPerSecond = intensity * 3f,
                validPanSamples = 3,
                validTiltSamples = 3,
                panDegreesPerSecond = intensity * 0.6f,
                tiltDegreesPerSecond = intensity * 0.5f,
            )
        },
        panLimits = GimbalAxisLimits(
            minimumDeg = -57f,
            maximumDeg = 235f,
            sweepIntensityPercent = 20,
            travelSecondsAtSweepIntensity = 48f,
            movingPulses = 24,
            endpointConfidencePercent = 90,
        ),
        tiltLimits = GimbalAxisLimits(
            minimumDeg = -57f,
            maximumDeg = 120f,
            sweepIntensityPercent = 20,
            travelSecondsAtSweepIntensity = 31f,
            movingPulses = 18,
            endpointConfidencePercent = 90,
        ),
        panAngularScale = 1.04f,
        tiltAngularScale = 0.98f,
    )

    @Test
    fun `il profilo esce e rientra identico`() {
        val original = profile()
        val reloaded = json.decodeFromString(
            GimbalCalibrationProfile.serializer(),
            json.encodeToString(GimbalCalibrationProfile.serializer(), original),
        )
        assertEquals(original, reloaded)
    }

    /** Quello che rientra deve essere utilizzabile, non solo uguale: è per questo che si salva. */
    @Test
    fun `il profilo riletto e' ancora valido`() {
        val original = profile()
        assertTrue("Il profilo di partenza dovrebbe essere valido: ${original.invalidReason}", original.isValid)
        val reloaded = json.decodeFromString(
            GimbalCalibrationProfile.serializer(),
            json.encodeToString(GimbalCalibrationProfile.serializer(), original),
        )
        assertTrue(reloaded.isValid)
        assertNull(reloaded.invalidReason)
        assertEquals(-57f, reloaded.panLimits.minimumDeg, 0.001f)
        assertEquals(235f, reloaded.panLimits.maximumDeg, 0.001f)
        assertEquals(9, reloaded.responsePoints.size)
    }

    /**
     * Un file di un'altra cosa non deve passare per calibrazione: meglio un messaggio che una
     * panoramica che finisce dove capita.
     */
    @Test
    fun `un json che non e' una calibrazione non diventa un profilo valido`() {
        val altro = json.decodeFromString(
            GimbalCalibrationProfile.serializer(),
            """{"cameraModel":"qualcos'altro"}""",
        )
        assertTrue(!altro.isValid)
    }

    /** Un profilo di uno schema precedente si riconosce e si rifiuta, invece di essere creduto. */
    @Test
    fun `un profilo di una versione precedente non e' valido`() {
        val vecchio = profile().copy(schemaVersion = 1)
        assertTrue(!vecchio.isValid)
        assertEquals("profilo di una versione precedente", vecchio.invalidReason)
    }
}
