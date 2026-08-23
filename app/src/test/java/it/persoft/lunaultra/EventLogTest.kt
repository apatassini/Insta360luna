package it.persoft.lunaultra

import it.persoft.lunaultra.net.EventLog
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventLogTest {

    @Test
    fun `la miniatura viene copiata e segnalata nell'esportazione testuale`() {
        val source = byteArrayOf(1, 2, 3)
        val log = EventLog()
        log.info("Punto A", "pan 1", source)
        source[0] = 9

        assertArrayEquals(byteArrayOf(1, 2, 3), log.entries.value.single().imageJpeg)
        assertTrue(log.exportText().contains("miniatura 256×256"))
    }
}
