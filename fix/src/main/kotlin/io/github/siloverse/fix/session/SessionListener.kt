package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixMessage

/**
 * What the application plugs into a [SessionDriver]. Called on the driver's threads, one
 * call at a time, in the order the session produced them. Keep implementations quick;
 * hand real work to your own executor.
 */
interface SessionListener {

    /** An in-order application message. 43=Y is still on it when it is a resend; deduplicate by your own key. */
    fun onMessage(message: FixMessage)

    /** Logon, disconnect, gap detected, peer rejected, and so on. [SessionEvent.Disconnect] is always the last event of a connection. */
    fun onEvent(event: SessionEvent)
}
