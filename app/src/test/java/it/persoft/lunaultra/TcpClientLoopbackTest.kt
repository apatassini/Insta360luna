package it.persoft.lunaultra

import it.persoft.lunaultra.net.EventLog
import it.persoft.lunaultra.net.TcpClient
import it.persoft.lunaultra.protocol.ProtoWriter
import it.persoft.lunaultra.protocol.Ucd2Codec
import it.persoft.lunaultra.protocol.Ucd2Frame
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

    @Test
    fun `i frame inviati dal server arrivano decodificati`() = runBlocking {
        val server = ServerSocket(0)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val codec = Ucd2Codec()
        val client = TcpClient(EventLog(), binder = null)

        val expected = Ucd2Frame(
            commandId = 0x2A,
            sequence = 3,
            type = Ucd2Frame.TYPE_RESPONSE,
            payload = ProtoWriter().int32(1, 77).toByteArray(),
        )

        val serverJob = scope.async {
            server.accept().use { connection ->
                val request = ByteArray(codec.layout.headerSize)
                connection.getInputStream().read(request)
                // Risponde in due pezzi, per esercitare anche la ricomposizione lato client.
                val response = codec.encode(expected)
                connection.getOutputStream().write(response, 0, 3)
                connection.getOutputStream().flush()
                delay(50)
                connection.getOutputStream().write(response, 3, response.size - 3)
                connection.getOutputStream().flush()
                delay(200)
                request
            }
        }

        try {
            val connectResult = client.connect("127.0.0.1", server.localPort, codec, scope)
            assertTrue(connectResult.isSuccess)

            val incoming = scope.async { client.frames.first() }
            delay(100)
            client.send(codec.encode(Ucd2Frame(commandId = 1, sequence = 1)))

            val frame = withTimeout(5_000) { incoming.await() }
            assertEquals(expected, frame)

            val requestBytes = withTimeout(5_000) { serverJob.await() }
            val decodedRequest = codec.decode(requestBytes, 0, requestBytes.size)
            assertTrue(decodedRequest is Ucd2Codec.DecodeResult.Frame)
            assertEquals(1, (decodedRequest as Ucd2Codec.DecodeResult.Frame).frame.commandId)
        } finally {
            client.disconnect()
            server.close()
            scope.cancel()
        }
    }
}
