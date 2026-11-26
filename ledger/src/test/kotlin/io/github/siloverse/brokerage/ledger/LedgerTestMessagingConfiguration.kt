package io.github.siloverse.brokerage.ledger

import io.github.siloverse.brokerage.ledger.api.messages.LedgerMessages
import io.github.siloverse.messaging.core.api.TransactionAwareAsynchronousBus
import io.github.siloverse.messaging.core.dispatch.DefaultTransactionAwareAsynchronousBus
import io.github.siloverse.messaging.core.transport.EnvelopeFactory
import io.github.siloverse.messaging.spring.outbox.JdbcOutboxWriter
import io.github.siloverse.messaging.spring.serialization.JacksonPayloadSerializer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.JdbcTemplate
import tools.jackson.databind.ObjectMapper

/**
 * The publishing half of the messaging library, for the ledger's integration tests: the
 * real bus and the real outbox writer, so a test can read the row a transfer leaves in
 * `messaging_outbox`. No relay and no broker; `app` wires those.
 */
@Configuration
class LedgerTestMessagingConfiguration {

    @Bean
    fun transactionAwareAsynchronousBus(jdbcTemplate: JdbcTemplate, mapper: ObjectMapper): TransactionAwareAsynchronousBus =
        DefaultTransactionAwareAsynchronousBus(
            EnvelopeFactory(LedgerMessages.names(), { }, JacksonPayloadSerializer(mapper)),
            JdbcOutboxWriter(jdbcTemplate, mapper),
        )
}
