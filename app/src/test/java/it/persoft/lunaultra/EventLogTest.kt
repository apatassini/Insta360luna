package it.persoft.lunaultra

import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.LogLevel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
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

    /**
     * Una calibrazione riempie il log di conferme vuote: se lo scarto fosse cronologico si
     * porterebbe via proprio le righe che spiegano com'è andata.
     */
    @Test
    fun `il traffico viene scartato prima delle righe che raccontano l'esito`() {
        val log = EventLog(capacity = 10)
        log.info("Avvio calibrazione")
        repeat(30) { log.rx("RISPOSTA_OK $it") }

        val entries = log.entries.value
        assertEquals(10, entries.size)
        assertEquals("Avvio calibrazione", entries.first().message)
        assertTrue(entries.drop(1).all { it.level == LogLevel.RX })
    }

    @Test
    fun `quando restano solo righe di racconto si scartano le piu vecchie`() {
        val log = EventLog(capacity = 3)
        repeat(5) { log.info("Passo $it") }

        assertEquals(listOf("Passo 2", "Passo 3", "Passo 4"), log.entries.value.map { it.message })
    }
}
