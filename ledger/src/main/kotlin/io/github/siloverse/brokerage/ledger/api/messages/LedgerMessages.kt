package io.github.siloverse.brokerage.ledger.api.messages

import io.github.siloverse.brokerage.ledger.api.messages.events.TransferBooked
import io.github.siloverse.messaging.core.naming.MessageNameRegistry

object LedgerMessages {
    fun names(): MessageNameRegistry =
        MessageNameRegistry.builder()
            .register(TransferBooked::class.java, "ledger.transfer-booked")
            .freeze()
}