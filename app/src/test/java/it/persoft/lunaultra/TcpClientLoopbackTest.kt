package it.persoft.lunaultra

import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.TcpClient
import it.persoft.lunaultra.protocol.FrameAssembler
import it.persoft.lunaultra.protocol.ProtoWriter
import it.persoft.lunaultra.protocol.Ucd2
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket

/** Verifica il percorso completo socket → assembler → frame, su un server di prova in loopback. */
class TcpClientLoopbackTest {

    private fun response(code: Int, requestId: Int, body: ByteArray): ByteArray {
        val raw = ByteArray(Ucd2.COMMAND_HEADER_SIZE + body.size)
        Ucd2.putShortLe(raw, 0, code)
        raw[2] = Ucd2.DIRECTION_RESPONSE.toByte()
        Ucd2.putShortLe(raw, 3, requestId)
        Ucd2.putIntLe(raw, 5, Ucd2.COMMAND_CONSTANT)
        body.copyInto(raw, Ucd2.COMMAND_HEADER_SIZE)
        return Ucd2.frame(Ucd2.TYPE_FILE, sequence = 3, body = raw)
    }

    @Test
    fun `i frame inviati dal server arrivano decodificati`() = runBlocking {
        val server = ServerSocket(0)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val client = TcpClient(EventLog(), binder = null)

        val expectedBody = ProtoWriter().int32(1, 77).toByteArray()
        val responseBytes = response(code = 0x2A, requestId = 3, body = expectedBody)

        val serverJob = scope.async {
            server.accept().use { connection ->
                val request = ByteArray(64)
                val read = connection.getInputStream().read(request)
                // Risponde in due pezzi, per esercitare anche la ricomposizione lato client.
                connection.getOutputStream().write(responseBytes, 0, 3)
                connection.getOutputStream().flush()
                delay(50)
                connection.getOutputStream().write(responseBytes, 3, responseBytes.size - 3)
                connection.getOutputStream().flush()
                delay(200)
                request.copyOf(read)
            }
        }

        try {
            val connectResult = client.connect("127.0.0.1", server.localPort, scope)
            assertTrue(connectResult.isSuccess)

            val incoming = scope.async { client.frames.first() }
            delay(100)
            client.send(Ucd2.command(sequence = 1, code = 8, requestId = 1, body = ByteArray(0)))

            val frame = withTimeout(5_000) { incoming.await() }
            assertEquals(0x2A, frame.code)
            assertEquals(3, frame.requestId)
            assertTrue(expectedBody.contentEquals(frame.payload))

            val requestBytes = withTimeout(5_000) { serverJob.await() }
            val assembler = FrameAssembler()
            assembler.append(requestBytes, requestBytes.size)
            val decodedRequest = assembler.drain().single()
            assertEquals(8, decodedRequest.code)
            assertEquals(1, decodedRequest.requestId)
        } finally {
            client.disconnect()
            server.close()
            scope.cancel()
        }
    }
}
