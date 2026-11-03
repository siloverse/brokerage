package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.store.InMemoryMessageStore
import io.github.siloverse.fix.store.MessageStore
import java.time.Duration
import java.time.Instant

/**
 * Two sessions, BROKER (initiator) and VENUE (acceptor), joined by hand. No threads, no
 * sockets: [pump] carries bytes from one to the other until both fall silent. Tests drop,
 * duplicate or reorder bytes between the two by not calling [pump] and delivering
 * themselves. Stores outlive sessions so a "restart" is a new session on the same store.
 */
class SessionLink(
    brokerConfig: SessionConfig = SessionConfig("BROKER", "VENUE", SessionRole.INITIATOR, Duration.ofSeconds(30)),
    venueConfig: SessionConfig = SessionConfig("VENUE", "BROKER", SessionRole.ACCEPTOR, Duration.ofSeconds(20)),
    val brokerStore: MessageStore = InMemoryMessageStore(),
    val venueStore: MessageStore = InMemoryMessageStore(),
) {
    var broker = FixSession(brokerConfig, brokerStore)
    var venue = FixSession(venueConfig, venueStore)

    /** Everything each side's application and driver saw, in order. */
    val brokerApp = mutableListOf<FixMessage>()
    val venueApp = mutableListOf<FixMessage>()
    val brokerEvents = mutableListOf<SessionEvent>()
    val venueEvents = mutableListOf<SessionEvent>()

    /** Bytes waiting to be delivered to each side. */
    val toBroker = ArrayDeque<ByteArray>()
    val toVenue = ArrayDeque<ByteArray>()

    var now: Instant = Instant.parse("2026-11-03T10:00:00Z")

    fun advance(duration: Duration) { now = now.plus(duration) }

    /** Record an output: bytes go into the peer's queue, the rest into this side's lists. */
    fun collect(side: FixSession, output: SessionOutput) {
        if (side === broker) {
            toVenue += output.bytesToSend
            brokerApp += output.applicationMessages
            brokerEvents += output.events
        } else {
            toBroker += output.bytesToSend
            venueApp += output.applicationMessages
            venueEvents += output.events
        }
    }

    /** Deliver one queued chunk to [side], if any. Returns false when the queue was empty. */
    fun deliverOne(side: FixSession): Boolean {
        val queue = if (side === broker) toBroker else toVenue
        val bytes = queue.removeFirstOrNull() ?: return false
        if (side.state != SessionState.DISCONNECTED) collect(side, side.onBytes(bytes, now))
        return true
    }

    /** Carry bytes both ways until nothing is queued. */
    fun pump() {
        while (deliverOne(venue) || deliverOne(broker)) { /* keep going */ }
    }

    /** Full logon handshake from a fresh transport. */
    fun connect() {
        collect(broker, broker.connect(now))
        collect(venue, venue.connect(now))
        pump()
    }

    /** Both transports gone, nothing in flight survives. */
    fun dropConnection() {
        collect(broker, broker.disconnected())
        collect(venue, venue.disconnected())
        toBroker.clear()
        toVenue.clear()
    }

    /** A process restart on [side]: a new session object over the same store. */
    fun restartBroker() { broker = FixSession(broker.config, brokerStore) }
    fun restartVenue() { venue = FixSession(venue.config, venueStore) }

    fun brokerSends(msgType: String, vararg body: Pair<Int, String>) =
        collect(broker, broker.send(msgType, body.map { io.github.siloverse.fix.message.FixField(it.first, it.second) }, now))

    fun venueSends(msgType: String, vararg body: Pair<Int, String>) =
        collect(venue, venue.send(msgType, body.map { io.github.siloverse.fix.message.FixField(it.first, it.second) }, now))

    fun tickBoth() {
        collect(broker, broker.onTick(now))
        collect(venue, venue.onTick(now))
    }
}
