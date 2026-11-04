package io.github.siloverse.venue.engine

import io.github.siloverse.fix.message.FixField

/** One message the engine wants sent: its type and body. The session adds the header. */
class VenueReply(val msgType: String, val body: List<FixField>)
