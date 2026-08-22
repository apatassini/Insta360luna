package it.persoft.lunaultra

import it.persoft.lunaultra.media.Jpeg
import it.persoft.lunaultra.media.MediaIndex
import it.persoft.lunaultra.media.MediaKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La libreria si costruisce da un elenco di percorsi e da niente altro: nessun metadato, nessuna
 * dimensione, solo nomi. Le regole che li interpretano sono l'unico posto dove si può sbagliare
 * in silenzio — un video accoppiato al proxy sbagliato mostra l'anteprima di un'altra ripresa.
 */
class MediaIndexTest {

    private val camera = "/storage_internal/DCIM/Camera01"

    @Test
    fun `il proxy si accoppia al video e sparisce dall'elenco`() {
        val items = MediaIndex.fromPaths(
            listOf(
                "$camera/VID_20260718_142012_00_001.mp4",
                "$camera/LRV_20260718_142012_00_001.lrv",
            )
        )

        assertEquals(1, items.size)
        val video = items.single()
        assertEquals(MediaKind.VIDEO, video.kind)
        assertEquals("$camera/LRV_20260718_142012_00_001.lrv", video.proxyPath)
    }

    /** Il firmware 1.0.238 ha tolto il segmento del sottoflusso dai nomi: regge anche quelli. */
    @Test
    fun `il proxy si accoppia anche con i nomi senza sottoflusso`() {
        val items = MediaIndex.fromPaths(
            listOf("$camera/VID_20260718_142012_001.mp4", "$camera/LRV_20260718_142012_001.lrv")
        )
        assertEquals("$camera/LRV_20260718_142012_001.lrv", items.single().proxyPath)
    }

    @Test
    fun `i file di servizio delle foto in movimento non entrano in galleria`() {
        val items = MediaIndex.fromPaths(
            listOf("$camera/IMG_20260718_142012_00_002.jpg", "$camera/IMG_20260718_142012_00_002.live.mp4")
        )
        assertEquals(1, items.size)
        assertEquals("IMG_20260718_142012_00_002.jpg", items.single().name)
    }

    @Test
    fun `il DNG prende come anteprima il JPG gemello`() {
        val items = MediaIndex.fromPaths(
            listOf("$camera/IMG_20260718_142012_00_003.dng", "$camera/IMG_20260718_142012_00_003.jpg")
        )
        val dng = items.first { it.extension == "dng" }

        assertEquals("$camera/IMG_20260718_142012_00_003.jpg", dng.previewPath)
        assertEquals("$camera/IMG_20260718_142012_00_003.jpg", dng.displayPath)
        assertTrue(items.any { it.extension == "jpg" })
    }

    @Test
    fun `panoramiche e insp sono riconosciute come sferiche`() {
        val items = MediaIndex.fromPaths(
            listOf(
                "$camera/PANO_20260718_142012_00_004.jpg",
                "$camera/IMG_20260718_142013_00_005.insp",
                "$camera/IMG_20260718_142014_00_006.jpg",
            )
        )
        assertTrue(items.first { it.name.startsWith("PANO_") }.panoramic)
        assertTrue(items.first { it.extension == "insp" }.panoramic)
        assertTrue(!items.first { it.name.startsWith("IMG_20260718_142014") }.panoramic)
    }

    @Test
    fun `i piu recenti stanno in cima`() {
        val items = MediaIndex.fromPaths(
            listOf(
                "$camera/IMG_20260718_100000_00_001.jpg",
                "$camera/IMG_20260718_180000_00_002.jpg",
                "$camera/IMG_20260717_090000_00_003.jpg",
            )
        )
        assertEquals(
            listOf(
                "IMG_20260718_180000_00_002.jpg",
                "IMG_20260718_100000_00_001.jpg",
                "IMG_20260717_090000_00_003.jpg",
            ),
            items.map { it.name },
        )
    }

    @Test
    fun `i file che non sono media vengono ignorati`() {
        val items = MediaIndex.fromPaths(
            listOf("$camera/log.txt", "$camera/.thumbnails", "$camera/IMG_20260718_142012_00_007.jpg")
        )
        assertEquals(1, items.size)
    }

    @Test
    fun `l'orario di scatto si legge dal nome`() {
        val moment = MediaIndex.parseNameTimestamp("VID_20260718_142012_00_001.mp4")
        assertTrue(moment != null && moment > 0L)
        assertNull(MediaIndex.parseNameTimestamp("qualcosa_senza_data.jpg"))
    }

    /** La risposta della miniatura non ha forma nota: si cerca il JPEG dentro i byte. */
    @Test
    fun `il JPEG si estrae anche se incapsulato`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
        val wrapped = byteArrayOf(0x0A, 0x07) + jpeg + byteArrayOf(0x00)

        assertTrue(jpeg.contentEquals(Jpeg.extract(wrapped)))
        assertNull(Jpeg.extract(byteArrayOf(1, 2, 3, 4)))
    }
}
