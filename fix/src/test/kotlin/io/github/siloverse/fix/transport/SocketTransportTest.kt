package io.github.siloverse.fix.transport

import io.github.siloverse.fix.codec.FixDecoder
import io.github.siloverse.fix.codec.FixEncoder
import io.github.siloverse.fix.codec.FixFramer
import io.github.siloverse.fix.session.SessionMessages
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SocketTransportTest {

    @Test
    fun `a real socket carries framed messages both ways and reports the peer closing`() {
        ServerSocket(0).use { server ->
            val executor = Executors.newSingleThreadExecutor()
            val acceptorSide = executor.submit<SocketTransport> { SocketTransport.accept(server) }
            val initiator = SocketTransport.connect("127.0.0.1", server.localPort)
            val acceptor = acceptorSide.get(2, TimeUnit.SECONDS)

            val time = Instant.parse("2026-11-03T10:00:00Z")
            initiator.write(FixEncoder.encode(SessionMessages.logon("BROKER", "VENUE", 1, time, 30)))
            initiator.write(FixEncoder.encode(SessionMessages.heartbeat("BROKER", "VENUE", 2, time)))

            val framer = FixFramer()
            val received = mutableListOf<String>()
            while (received.size < 2) {
                val chunk = acceptor.read()
                check(chunk.isNotEmpty()) { "peer closed early" }
                received += framer.feed(chunk).map { FixDecoder.decode(it).msgType }
            }
            assertEquals(listOf("A", "0"), received)

            acceptor.write(FixEncoder.encode(SessionMessages.logon("VENUE", "BROKER", 1, time, 30)))
            assertEquals("A", FixDecoder.decode(FixFramer().feed(initiator.read()).single()).msgType)

            initiator.close()
            assertEquals(0, acceptor.read().size)
            acceptor.close()
            executor.shutdownNow()
        }
    }
}
