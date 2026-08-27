package it.persoft.lunaultra

import it.persoft.lunaultra.stitch.LayoutFrame
import it.persoft.lunaultra.stitch.PanoLayoutFinder
import it.persoft.lunaultra.stitch.PinholeLens
import it.persoft.lunaultra.stitch.FramePlacement
import it.persoft.lunaultra.stitch.frameToWorld
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs

/**
 * Trovare il posto delle foto senza che nessuno lo dica.
 *
 * Va provato qui e non sul telefono, perché sul telefono l'unica risposta è «la panoramica e`
 * venuta a ventaglio» — che non dice se il difetto sta nel riconoscere i dettagli, nel votare
 * lo spostamento, nel segno con cui lo si applica o nel modo in cui le posizioni si propagano.
 * Qui la griglia la decido io, e chiedo che venga ritrovata.
 */
class PanoLayoutTest {

    /**
     * Un mondo finto: macchie sparse su una sfera, che è quello che serve per riconoscere.
     *
     * Sta in coordinate del mondo (longitudine, latitudine) e da lì si ritagliano le foto: due
     * foto vicine vedono le stesse macchie, com'e` giusto, e ognuna le vede deformate dalla sua
     * prospettiva — che è esattamente il lavoro difficile.
     */
    private class World(seed: Long) {
        private val blobs = ArrayList<FloatArray>()

        init {
            val random = Random(seed)
            repeat(3000) {
                blobs += floatArrayOf(
                    random.nextFloat() * 360f - 180f,
                    random.nextFloat() * 120f - 60f,
                    1.5f + random.nextFloat() * 2.5f,
                    (40 + random.nextInt(180)).toFloat(),
                )
            }
        }

        /** Quanto è chiara una direzione del mondo: fondo grigio più le macchie che la coprono. */
        fun toneAt(lon: Float, lat: Float): Float {
            var tone = 120f + lat * 0.4f
            blobs.forEach { blob ->
                val dLon = (lon - blob[0]) * kotlin.math.cos(Math.toRadians(lat.toDouble())).toFloat()
                val dLat = lat - blob[1]
                if (abs(dLon) > blob[2] || abs(dLat) > blob[2]) return@forEach
                if (dLon * dLon + dLat * dLat <= blob[2] * blob[2]) tone = blob[3]
            }
            return tone
        }
    }

    /** Una foto scattata dal mondo finto, con la sua lente e il suo orientamento. */
    private fun shoot(
        world: World,
        label: String,
        panDegrees: Float,
        tiltDegrees: Float,
        fovDegrees: Float,
        width: Int = 320,
        height: Int = 240,
    ): LayoutFrame {
        val lens = PinholeLens(width, height, fovDegrees)
        val placement = FramePlacement(panDegrees = panDegrees, tiltDegrees = tiltDegrees)
        val gray = ByteArray(width * height)
        // Un pixel per volta, chiedendo alla lente dove guarda: e` il giro giusto — nessun
        // buco, e nessun pixel scritto due volte.
        for (y in 0 until height) {
            for (x in 0 until width) {
                val world0 = frameToWorld(x.toFloat(), y.toFloat(), placement, lens)
                gray[y * width + x] = world.toneAt(world0[0], world0[1])
                    .toInt().coerceIn(0, 255).toByte()
            }
        }
        return LayoutFrame(label, gray, width, height)
    }

    private fun spread(spots: List<Float>): List<Float> {
        val mean = spots.average().toFloat()
        return spots.map { it - mean }
    }

    @Test
    fun `una fila di tre foto viene rimessa in fila`() = runBlocking {
        val world = World(seed = 7)
        val fov = 60f
        val truth = listOf(-40f, 0f, 40f)
        // Volutamente in disordine: è il caso vero, l'ordine del selettore non vuol dire niente.
        val order = listOf(2, 0, 1)
        val frames = order.map { shoot(world, "Foto ${it + 1}", truth[it], 0f, fov) }

        val layout = PanoLayoutFinder.solve(frames, fov)

        assertTrue("tutte piazzate", layout.allPlaced)
        val expected = spread(order.map { truth[it] })
        layout.spots.forEachIndexed { index, spot ->
            assertEquals(
                "pan di ${frames[index].label}",
                expected[index].toDouble(),
                spot.panDegrees.toDouble(),
                2.0,
            )
            assertEquals("tilt di ${frames[index].label}", 0.0, spot.tiltDegrees.toDouble(), 2.0)
        }
    }

    @Test
    fun `una griglia due per due non viene srotolata in una striscia`() = runBlocking {
        val world = World(seed = 11)
        val fov = 60f
        val truthPan = listOf(-21f, 21f, -21f, 21f)
        val truthTilt = listOf(16f, 16f, -16f, -16f)
        val order = listOf(3, 1, 2, 0)
        val frames = order.map { shoot(world, "Foto ${it + 1}", truthPan[it], truthTilt[it], fov) }

        val layout = PanoLayoutFinder.solve(frames, fov)

        assertTrue("tutte piazzate", layout.allPlaced)
        val expectedPan = spread(order.map { truthPan[it] })
        val expectedTilt = spread(order.map { truthTilt[it] })
        layout.spots.forEachIndexed { index, spot ->
            assertEquals(
                "pan di ${frames[index].label}",
                expectedPan[index].toDouble(),
                spot.panDegrees.toDouble(),
                3.0,
            )
            assertEquals(
                "tilt di ${frames[index].label}",
                expectedTilt[index].toDouble(),
                spot.tiltDegrees.toDouble(),
                3.0,
            )
        }
    }

    @Test
    fun `una foto che non c'entra niente non viene attaccata a caso`() = runBlocking {
        val world = World(seed = 13)
        val altrove = World(seed = 999)
        val fov = 60f
        val frames = listOf(
            shoot(world, "Foto 1", -30f, 0f, fov),
            shoot(world, "Foto 2", 10f, 0f, fov),
            shoot(altrove, "Estranea", 0f, 0f, fov),
        )

        val layout = PanoLayoutFinder.solve(frames, fov)

        val toTheStranger = layout.links.filter { it.a == 2 || it.b == 2 }
        assertTrue("nessuna giunzione con l'estranea: $toTheStranger", toTheStranger.isEmpty())
        assertTrue("l'estranea resta senza posto", !layout.spots[2].placed)
        assertTrue("le due vere si trovano", layout.links.isNotEmpty())
    }

    /**
     * Venti foto, quattro file da cinque, mescolate.
     *
     * È la prova che conta davvero: con venti foto le coppie da provare sono centonovanta, e
     * basta **una** giunzione inventata fra due foto che non si toccano per mandare fuori posto
     * un ramo intero dell'albero. Qui si chiede che non ne nasca nessuna, e che l'errore non
     * cresca lungo la catena — la foto più lontana dall'ancora è cinque passi più in là.
     */
    /**
     * Il caso vero: si scelgono sei foto e quattro sono una panoramica, due un altro momento.
     *
     * Succede sempre, perché nella cartella del telefono gli scatti stanno di fianco e chi
     * sceglie ne prende qualcuna di troppo. Prima si mettevano tutte in fila lo stesso, e due
     * scatti che non c'entravano allargavano la tela di ottanta gradi per stare in un angolo.
     * Adesso ogni gruppo di foto legate fra loro è una panoramica possibile, e si tiene la più
     * grande — che è quella che si voleva.
     */
    @Test
    fun `le foto di un altro momento restano fuori, e resta la panoramica piu' grande`() = runBlocking {
        val panorama = World(seed = 31)
        val altrove = World(seed = 77)
        val fov = 60f
        val frames = listOf(-63f, -21f, 21f, 63f).mapIndexed { index, pan ->
            shoot(panorama, "Foto ${index + 1}", pan, 0f, fov)
        } + listOf(-20f, 20f).mapIndexed { index, pan ->
            shoot(altrove, "Estranea ${index + 1}", pan, 0f, fov)
        }

        val layout = PanoLayoutFinder.solve(frames, fov)

        val kept = layout.spots.indices.filter { layout.spots[it].placed }
        assertEquals("la panoramica tenuta è quella da quattro", listOf(0, 1, 2, 3), kept)
        // E le due estranee non spariscono: sono una panoramica loro, e chi le ha scelte
        // deve poterne fare un lavoro a parte invece di ritrovarsele buttate.
        assertEquals("due panoramiche trovate", 2, layout.groups.size)
        assertEquals("la seconda è quella da due", listOf(4, 5), layout.groups[1])
        // E le quattro tenute stanno dove devono, non appiccicate a caso.
        val expected = spread(listOf(-63f, -21f, 21f, 63f))
        kept.forEach { index ->
            assertEquals(
                "pan di ${frames[index].label}",
                expected[index].toDouble(),
                layout.spots[index].panDegrees.toDouble(),
                3.0,
            )
        }
    }

    @Test
    fun `venti foto in quattro file si rimettono a posto`() = runBlocking {
        val world = World(seed = 21)
        val fov = 60f
        val truthPan = mutableListOf<Float>()
        val truthTilt = mutableListOf<Float>()
        listOf(36f, 12f, -12f, -36f).forEach { tilt ->
            listOf(-84f, -42f, 0f, 42f, 84f).forEach { pan ->
                truthPan += pan
                truthTilt += tilt
            }
        }
        // Mescolate a mano con un seme fisso: l'ordine deve essere sempre lo stesso, e deve
        // essere sbagliato — è il caso vero.
        val order = (0 until 20).toMutableList().also { list ->
            val random = Random(3)
            for (i in list.indices.reversed()) {
                val j = random.nextInt(i + 1)
                val keep = list[i]
                list[i] = list[j]
                list[j] = keep
            }
        }
        val frames = order.map { shoot(world, "Foto ${it + 1}", truthPan[it], truthTilt[it], fov) }

        val layout = PanoLayoutFinder.solve(frames, fov)

        assertTrue("tutte piazzate", layout.allPlaced)
        // Nessuna giunzione fra foto che non possono sovrapporsi: è l'errore che rovina tutto.
        layout.links.forEach { link ->
            val a = order[link.a]
            val b = order[link.b]
            assertTrue(
                "giunzione impossibile fra ${frames[link.a].label} e ${frames[link.b].label}",
                abs(truthPan[a] - truthPan[b]) <= 60f && abs(truthTilt[a] - truthTilt[b]) <= 50f,
            )
        }
        val expectedPan = spread(order.map { truthPan[it] })
        val expectedTilt = spread(order.map { truthTilt[it] })
        layout.spots.forEachIndexed { index, spot ->
            assertEquals(
                "pan di ${frames[index].label}",
                expectedPan[index].toDouble(),
                spot.panDegrees.toDouble(),
                3.0,
            )
            assertEquals(
                "tilt di ${frames[index].label}",
                expectedTilt[index].toDouble(),
                spot.tiltDegrees.toDouble(),
                3.0,
            )
        }
    }
}
