package io.github.siloverse.brokerage.ledger.api.messages.events

import io.github.siloverse.messaging.core.api.Event
import java.util.UUID

data class TransferBooked(val transferUuid: UUID, val kind: String) : Event