package it.persoft.lunaultra

import it.persoft.lunaultra.stitch.FramePlacement
import it.persoft.lunaultra.stitch.LocalWarp
import it.persoft.lunaultra.stitch.PanoramaCanvas
import it.persoft.lunaultra.stitch.PinholeLens
import it.persoft.lunaultra.stitch.angularDistance
import it.persoft.lunaultra.stitch.exposureGain
import it.persoft.lunaultra.stitch.featherWeight
import it.persoft.lunaultra.stitch.pixelsToDegrees
import it.persoft.lunaultra.stitch.projectToFrame
import it.persoft.lunaultra.stitch.sphericalCoverage
import it.persoft.lunaultra.stitch.sampleSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * La geometria dell'unione, che è la parte che deve essere giusta.
 *
 * Se la proiezione inversa sbaglia, nessuna sfumatura salva la giunzione: due fotogrammi che
 * non combaciano restano due fotogrammi che non combaciano, e mescolarli produce solo una zona
 * sfocata al posto di uno scalino netto. Il resto dello stitcher è ottimizzazione e memoria;
 * questo è il pezzo che decide se il risultato è una panoramica o un collage.
 *
 * I numeri sono quelli veri della Luna Ultra: campo visivo 81,7° × 66,0° a 1x nel rapporto 4:3.
 */
class PanoramaGeometryTest {

    private val lens = PinholeLens(imageWidth = 1600, imageHeight = 1200, horizontalFovDegrees = 81.7f)

    @Test
    fun `il campo verticale discende dalla focale, non si presume`() {
        // 4:3 con 81,7° di campo orizzontale dà 66° verticali: è il numero che l'app mostra
        // nel pannello della panoramica, e deve venire fuori dalla stessa geometria.
        assertEquals(66.0f, lens.verticalFovDegrees, 0.5f)
    }

    @Test
    fun `il centro dell'inquadratura e il centro dell'immagine`() {
        val placement = FramePlacement(panDegrees = 30f, tiltDegrees = -10f)
        val point = projectToFrame(30f, -10f, placement, lens)
        assertTrue(point.inside)
        assertEquals(800f, point.x, 0.5f)
        assertEquals(600f, point.y, 0.5f)
    }

    @Test
    fun `il bordo del campo cade sul bordo dell'immagine`() {
        val placement = FramePlacement(panDegrees = 0f, tiltDegrees = 0f)
        val edge = projectToFrame(81.7f / 2f, 0f, placement, lens)
        assertEquals(1599f, edge.x, 2f)
    }

    @Test
    fun `i bordi sono stirati rispetto al centro, ed e' la deformazione da correggere`() {
        val placement = FramePlacement(panDegrees = 0f, tiltDegrees = 0f)
        // Dieci gradi al centro coprono meno pixel di dieci gradi verso il bordo: è la firma
        // della proiezione rettilineare, e incollare le foto senza tenerne conto lascia una
        // giunzione visibile proprio dove i fotogrammi si toccano, cioè ai bordi.
        val centre = projectToFrame(10f, 0f, placement, lens).x - projectToFrame(0f, 0f, placement, lens).x
        val outer = projectToFrame(40f, 0f, placement, lens).x - projectToFrame(30f, 0f, placement, lens).x
        assertTrue("i dieci gradi esterni devono coprire più pixel di quelli centrali", outer > centre * 1.3f)
    }

    @Test
    fun `fuori dal campo il punto non e' dentro l'immagine`() {
        val placement = FramePlacement(panDegrees = 0f, tiltDegrees = 0f)
        assertFalse(projectToFrame(60f, 0f, placement, lens).inside)
        assertFalse(projectToFrame(0f, 50f, placement, lens).inside)
    }

    @Test
    fun `un raggio dietro la camera non ha nessun pixel che lo guardi`() {
        val placement = FramePlacement(panDegrees = 0f, tiltDegrees = 0f)
        assertFalse(projectToFrame(150f, 0f, placement, lens).inside)
        assertFalse(projectToFrame(180f, 0f, placement, lens).inside)
    }

    @Test
    fun `la correzione sposta il fotogramma di quanto le si chiede`() {
        val nominal = FramePlacement(panDegrees = 20f, tiltDegrees = 0f)
        val corrected = nominal.copy(panCorrectionDegrees = 1.5f)
        assertEquals(21.5f, corrected.effectivePan, 0.001f)
        // Guardando la stessa direzione, il fotogramma corretto la vede spostata: è così che la
        // raffinatura rimette a posto lo scarto della calibrazione.
        val before = projectToFrame(20f, 0f, nominal, lens).x
        val after = projectToFrame(20f, 0f, corrected, lens).x
        assertTrue(abs(after - before) > 20f)
    }

    @Test
    fun `il tilt ruota davvero, e non e' un semplice scorrimento verticale`() {
        val flat = FramePlacement(panDegrees = 0f, tiltDegrees = 0f)
        val raised = FramePlacement(panDegrees = 0f, tiltDegrees = 30f)
        // Guardando 30° in alto, il centro del fotogramma alzato di 30° è il suo centro esatto.
        val point = projectToFrame(0f, 30f, raised, lens)
        assertEquals(800f, point.x, 1f)
        assertEquals(600f, point.y, 1f)
        // Sul fotogramma piatto la stessa direzione cade in alto, ma non fuori.
        val onFlat = projectToFrame(0f, 30f, flat, lens)
        assertTrue(onFlat.inside)
        assertTrue("deve stare nella metà alta", onFlat.y < 600f)
    }

    @Test
    fun `la tela contiene tutti i fotogrammi piu' mezzo campo per parte`() {
        val placements = listOf(
            FramePlacement(-19f, 0f),
            FramePlacement(19f, 0f),
        )
        val canvas = PanoramaCanvas.covering(placements, lens, requestedPixelsPerDegree = 20f, maximumLongSide = 5000)
        // 38° fra i due centri più 81,7° di campo: la copertura vera è più larga di quella
        // chiesta, perché la griglia arrotonda sempre per eccesso.
        assertEquals(38f + 81.7f, canvas.horizontalDegrees, 0.5f)
        assertEquals(66f, canvas.verticalDegrees, 0.5f)
        assertEquals(0f, canvas.centerPanDegrees, 0.01f)
    }

    @Test
    fun `il tetto sul lato lungo tiene la tela dentro la memoria`() {
        val placements = listOf(FramePlacement(-90f, 0f), FramePlacement(90f, 0f))
        val canvas = PanoramaCanvas.covering(placements, lens, requestedPixelsPerDegree = 100f, maximumLongSide = 5000)
        assertTrue("il lato lungo non deve superare il tetto", canvas.width <= 5000)
        assertTrue(canvas.height <= 5000)
        // Il rapporto fra i lati resta quello dei gradi coperti: il tetto scala, non ritaglia.
        val degreesRatio = canvas.horizontalDegrees / canvas.verticalDegrees
        assertEquals(degreesRatio, canvas.width.toFloat() / canvas.height, 0.05f)
    }

    @Test
    fun `le coordinate della tela vanno da un estremo all'altro`() {
        val canvas = PanoramaCanvas(0f, 0f, 100f, 50f, pixelsPerDegree = 10f)
        assertEquals(1000, canvas.width)
        assertEquals(500, canvas.height)
        assertEquals(-50f, canvas.longitudeAt(0), 0.1f)
        assertEquals(50f, canvas.longitudeAt(canvas.width - 1), 0.1f)
        // La prima riga è la più alta: è la convenzione di ogni immagine.
        assertTrue(canvas.latitudeAt(0) > canvas.latitudeAt(canvas.height - 1))
        assertEquals(25f, canvas.latitudeAt(0), 0.1f)
    }

    @Test
    fun `la sfumatura vale uno al centro e zero sul bordo`() {
        assertEquals(1f, featherWeight(800f, 600f, 1600, 1200), 0.01f)
        assertEquals(0f, featherWeight(0f, 600f, 1600, 1200), 0.001f)
        assertEquals(0f, featherWeight(1599f, 600f, 1600, 1200), 0.001f)
        assertEquals(0f, featherWeight(800f, 0f, 1600, 1200), 0.001f)
    }

    @Test
    fun `la sfumatura cresce senza salti avvicinandosi al centro`() {
        var previous = -1f
        listOf(0f, 50f, 150f, 300f, 500f, 600f).forEach { y ->
            val weight = featherWeight(800f, y, 1600, 1200)
            assertTrue("il peso deve crescere verso il centro", weight >= previous)
            previous = weight
        }
    }

    @Test
    fun `la correzione di luminosita' si limita quando la sovrapposizione mente`() {
        assertEquals(1f, exposureGain(100f, 100f), 0.001f)
        assertEquals(1.25f, exposureGain(125f, 100f), 0.001f)
        // Un rapporto assurdo non è esposizione diversa: è che le due zone confrontate
        // guardavano cose diverse, e correggere peggiorerebbe.
        assertEquals(1.7f, exposureGain(1000f, 10f), 0.001f)
        assertEquals(0.6f, exposureGain(10f, 1000f), 0.001f)
        // Senza dati non si inventa una correzione.
        assertEquals(1f, exposureGain(0f, 100f), 0.001f)
    }

    @Test
    fun `un grado di longitudine vale meno pixel man mano che si sale`() {
        val atHorizon = pixelsToDegrees(100f, pixelsPerDegree = 10f, latitudeDegrees = 0f, horizontal = true)
        val high = pixelsToDegrees(100f, pixelsPerDegree = 10f, latitudeDegrees = 60f, horizontal = true)
        assertEquals(10f, atHorizon, 0.01f)
        // A sessanta gradi il coseno è mezzo: gli stessi pixel sono il doppio dei gradi.
        assertEquals(20f, high, 0.1f)
        // In verticale i meridiani non c'entrano e la conversione è diretta.
        assertEquals(10f, pixelsToDegrees(100f, 10f, 60f, horizontal = false), 0.01f)
    }

    @Test
    fun `la distanza angolare dice quali fotogrammi si toccano`() {
        assertEquals(0f, angularDistance(10f, 5f, 10f, 5f), 0.01f)
        assertEquals(30f, angularDistance(0f, 0f, 30f, 0f), 0.01f)
        assertEquals(20f, angularDistance(0f, -10f, 0f, 10f), 0.01f)
        // In alto i meridiani si stringono: 30° di longitudine a 60° di latitudine sono meno
        // di 30° di distanza vera, ed è la ragione per cui non basta sottrarre i gradi.
        assertTrue(angularDistance(0f, 60f, 30f, 60f) < 30f)
    }

    @Test
    fun `il fattore di riduzione e' una potenza di due e non scende mai sotto uno`() {
        assertEquals(1, sampleSizeFor(1600, 1600))
        assertEquals(2, sampleSizeFor(3200, 1600))
        assertEquals(4, sampleSizeFor(6400, 1600))
        assertEquals(1, sampleSizeFor(800, 1600))
        assertEquals(1, sampleSizeFor(0, 1600))
    }

    @Test
    fun `lo scatto sferico porta i centri fino agli estremi della corsa`() {
        // I limiti veri della Luna Ultra, misurati dalla calibrazione.
        val coverage = sphericalCoverage(
            panMinimumDeg = -57f,
            panMaximumDeg = 235f,
            tiltMinimumDeg = -57f,
            tiltMaximumDeg = 120f,
            horizontalFovDegrees = 81.7f,
            verticalFovDegrees = 66f,
            marginDegrees = 2f,
        )
        // La copertura chiesta al pianificatore è la corsa più un campo intero, perché il
        // pianificatore toglie mezzo campo per parte per ricavare dove vanno i centri. Chiedere
        // solo la corsa lascerebbe i centri all'interno, e la camera non guarderebbe mai
        // davvero in basso — che è proprio dove serve arrivare.
        assertEquals(288f + 81.7f, coverage.horizontalDegrees, 0.1f)
        assertEquals(173f + 66f, coverage.verticalDegrees, 0.1f)
        // Il centro è quello della corsa, non l'inquadratura attuale: una sfera non ha un davanti.
        assertEquals(89f, coverage.centerPanDegrees, 0.1f)
        assertEquals(31.5f, coverage.centerTiltDegrees, 0.1f)
    }

    @Test
    fun `i centri stanno dentro la corsa, con il margine che li tiene lontani dal limite`() {
        val coverage = sphericalCoverage(
            panMinimumDeg = -57f, panMaximumDeg = 235f,
            tiltMinimumDeg = -57f, tiltMaximumDeg = 120f,
            horizontalFovDegrees = 81.7f, verticalFovDegrees = 66f,
            marginDegrees = 2f,
        )
        val panCenterSpan = coverage.horizontalDegrees - 81.7f
        val lowestCenter = coverage.centerPanDegrees - panCenterSpan / 2f
        val highestCenter = coverage.centerPanDegrees + panCenterSpan / 2f
        assertTrue("il primo centro deve stare dentro la corsa", lowestCenter > -57f)
        assertTrue("l'ultimo centro deve stare dentro la corsa", highestCenter < 235f)
    }

    @Test
    fun `a 1x il giro si chiude, da 2x in su no`() {
        // Un fotogramma non è una linea: il primo scatto vede mezzo campo prima del suo centro
        // e l'ultimo mezzo campo dopo. A 1x i 292° di corsa piu' gli 81,7° di campo fanno
        // 373,7, e il giro si chiude davvero. A 2x il campo si stringe a 45,9 e non basta piu'.
        val wide = sphericalCoverage(
            panMinimumDeg = -57f, panMaximumDeg = 235f,
            tiltMinimumDeg = -57f, tiltMaximumDeg = 120f,
            horizontalFovDegrees = 81.7f, verticalFovDegrees = 66f,
        )
        assertTrue("a 1x il giro deve chiudersi", wide.closesTheCircle)
        assertEquals(0f, wide.missingHorizontalDegrees, 0.01f)

        val tele = sphericalCoverage(
            panMinimumDeg = -57f, panMaximumDeg = 235f,
            tiltMinimumDeg = -57f, tiltMaximumDeg = 120f,
            horizontalFovDegrees = 45.9f, verticalFovDegrees = 34.4f,
        )
        assertFalse("a 2x il giro non si chiude", tele.closesTheCircle)
        assertEquals(26.1f, tele.missingHorizontalDegrees, 0.5f)
    }

    @Test
    fun `la tela non ricomincia da capo quando l'arco supera il giro`() {
        // Con i centri sparsi su tutta la corsa l'arco coperto va oltre i 360: senza tetto la
        // tela ridisegnerebbe longitudini gia' fatte, e la panoramica avrebbe un pezzo doppio.
        val placements = listOf(FramePlacement(-55f, 0f), FramePlacement(233f, 0f))
        val canvas = PanoramaCanvas.covering(placements, lens, requestedPixelsPerDegree = 10f, maximumLongSide = 8000)
        assertEquals(360f, canvas.horizontalDegrees, 0.01f)
    }

    @Test
    fun `la tela non va oltre i poli, dove non c'e' piu' sfera`() {
        // Il fotogramma più alto di uno scatto sferico guarda a 120°: il suo bordo superiore
        // finirebbe a 153°, una latitudine che non esiste e in cui la proiezione si ribalta.
        val placements = listOf(FramePlacement(0f, -57f), FramePlacement(0f, 120f))
        val canvas = PanoramaCanvas.covering(placements, lens, requestedPixelsPerDegree = 10f, maximumLongSide = 5000)
        assertTrue("la tela non deve superare il polo nord", canvas.latitudeAt(0) <= 90.01f)
        assertTrue(
            "la tela non deve superare il polo sud",
            canvas.latitudeAt(canvas.height - 1) >= -90.01f,
        )
        assertEquals(180f, canvas.verticalDegrees, 0.5f)
    }

    @Test
    fun `la deformazione locale riproduce lo spostamento che le e' stato insegnato`() {
        // Tutti i punti dicono la stessa cosa: «sei fuori di cinque pixel a destra e tre in
        // alto». Il campo deve rispondere quello, ovunque ci siano punti a sostenerlo.
        val points = buildList {
            for (y in 100..1100 step 100) {
                for (x in 100..1500 step 100) {
                    add(floatArrayOf(x.toFloat(), y.toFloat(), 5f, -3f))
                }
            }
        }
        val warp = LocalWarp.from(points, 1600, 1200, maximumShiftPixels = 40f)
        assertTrue("con centocinquanta punti concordi il campo deve esistere", warp != null)
        assertEquals(5f, warp!!.shiftX(800f, 600f), 0.3f)
        assertEquals(-3f, warp.shiftY(800f, 600f), 0.3f)
    }

    @Test
    fun `la deformazione locale non si fida di quattro punti`() {
        val points = listOf(
            floatArrayOf(100f, 100f, 9f, 9f),
            floatArrayOf(200f, 200f, 9f, 9f),
            floatArrayOf(300f, 300f, 9f, 9f),
            floatArrayOf(400f, 400f, 9f, 9f),
        )
        assertTrue(LocalWarp.from(points, 1600, 1200, maximumShiftPixels = 40f) == null)
    }

    @Test
    fun `la deformazione locale non sposta mai oltre il limite`() {
        // Punti che pretendono duecento pixel: il limite li riporta alla ragione. Serve a
        // impedire che una manciata di corrispondenze sbagliate stravolga un fotogramma.
        val points = buildList {
            for (y in 100..1100 step 100) {
                for (x in 100..1500 step 100) {
                    add(floatArrayOf(x.toFloat(), y.toFloat(), 200f, -200f))
                }
            }
        }
        val warp = LocalWarp.from(points, 1600, 1200, maximumShiftPixels = 40f)!!
        assertEquals(40f, warp.shiftX(800f, 600f), 0.01f)
        assertEquals(-40f, warp.shiftY(800f, 600f), 0.01f)
    }
}
