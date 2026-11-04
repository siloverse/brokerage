package io.github.siloverse.venue.config

import io.github.siloverse.fix.session.SessionConfig
import io.github.siloverse.fix.session.SessionRole
import io.github.siloverse.fix.store.FileMessageStore
import io.github.siloverse.venue.engine.EngineConfig
import io.github.siloverse.venue.engine.VenueEngine
import io.github.siloverse.venue.fix.VenueFixServer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Wires the engine and the FIX server from [VenueProperties]. */
@Configuration
class VenueConfiguration {

    @Bean
    fun venueEngine(properties: VenueProperties): VenueEngine = VenueEngine(
        EngineConfig(
            fillDelay = properties.engine.fillDelay,
            partialFills = properties.engine.partialFills,
            startPrice = properties.engine.startPrice,
            priceStep = properties.engine.priceStep,
            sessionClose = properties.engine.sessionClose,
            zone = properties.engine.zone,
        ),
    )

    @Bean
    fun venueFixServer(properties: VenueProperties, engine: VenueEngine): VenueFixServer = VenueFixServer(
        engine = engine,
        sessionConfig = SessionConfig(properties.senderCompId, properties.targetCompId, SessionRole.ACCEPTOR, properties.heartBtInt),
        store = FileMessageStore(properties.storeDir.resolve("${properties.senderCompId}-${properties.targetCompId}")),
        fixPort = properties.fixPort,
        outboundQueueLimit = properties.outboundQueueLimit,
    )

    @Bean
    fun venueFixServerLifecycle(server: VenueFixServer): VenueFixServerLifecycle = VenueFixServerLifecycle(server)
}
