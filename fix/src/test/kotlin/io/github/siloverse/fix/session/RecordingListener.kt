package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixMessage
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/** Collects everything and lets a test wait for a condition instead of sleeping blindly. */
class RecordingListener : SessionListener {

    val messages = CopyOnWriteArrayList<FixMessage>()
    val events = CopyOnWriteArrayList<SessionEvent>()

    override fun onMessage(message: FixMessage) { messages += message }

    override fun onEvent(event: SessionEvent) { events += event }

    /** Polls every 10 ms until [condition] holds or [timeout] passes. Returns whether it held. */
    fun await(timeout: Duration = Duration.ofSeconds(5), condition: RecordingListener.() -> Boolean): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    fun loggedOn(times: Int = 1) = events.count { it == SessionEvent.LoggedOn } >= times
    fun disconnected(times: Int = 1) = events.count { it is SessionEvent.Disconnect } >= times
}
