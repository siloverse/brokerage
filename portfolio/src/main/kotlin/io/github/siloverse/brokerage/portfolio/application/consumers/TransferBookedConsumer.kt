package io.github.siloverse.brokerage.portfolio.application.consumers

import io.github.siloverse.brokerage.ledger.api.messages.events.TransferBooked
import io.github.siloverse.messaging.core.consumer.Consumer
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

@Component
class TransferBookedConsumer {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Consumer(id = "portfolio-transfer-booked")
    fun consume(event: TransferBooked) {
        logger.info("transfer booked: transferUuid={} kind={}", event.transferUuid, event.kind)
    }
}