package io.github.siloverse.venue.config

import io.github.siloverse.venue.fix.VenueFixServer
import org.springframework.context.SmartLifecycle

/** Starts the FIX acceptor when the Spring context is up and stops it on shutdown. */
class VenueFixServerLifecycle(private val server: VenueFixServer) : SmartLifecycle {

    @Volatile private var running = false

    override fun start() {
        server.start()
        running = true
    }

    override fun stop() {
        server.stop()
        running = false
    }

    override fun isRunning(): Boolean = running
}
