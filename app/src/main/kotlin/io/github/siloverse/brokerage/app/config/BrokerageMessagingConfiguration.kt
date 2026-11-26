package io.github.siloverse.brokerage.app.config

import com.rabbitmq.client.Connection
import io.github.siloverse.brokerage.app.consumers.BrokerageRabbitListener
import io.github.siloverse.brokerage.ledger.api.messages.LedgerMessages
import io.github.siloverse.messaging.core.consumer.ConsumerRegistry
import io.github.siloverse.messaging.core.dispatch.MessageDispatcher
import io.github.siloverse.messaging.core.naming.MessageNameRegistry
import io.github.siloverse.messaging.core.transport.MessageTransport
import io.github.siloverse.messaging.core.transport.PayloadSerializer
import io.github.siloverse.messaging.rabbitmq.connection.RabbitMqConnectionSettings
import io.github.siloverse.messaging.rabbitmq.connection.RabbitMqConnector
import io.github.siloverse.messaging.rabbitmq.retry.FailedDeliveryHandler
import io.github.siloverse.messaging.rabbitmq.retry.RetrySettings
import io.github.siloverse.messaging.rabbitmq.retry.RetryingFailedDeliveryHandler
import io.github.siloverse.messaging.rabbitmq.topology.RabbitMqTopologyDeclarer
import io.github.siloverse.messaging.rabbitmq.transport.RabbitMqMessageTransport
import io.github.siloverse.messaging.spring.config.AsyncMessagingConfiguration
import io.github.siloverse.messaging.spring.config.MessagingConfiguration
import io.github.siloverse.messaging.spring.listener.MessageListener
import io.github.siloverse.messaging.spring.serialization.JacksonPayloadSerializer
import io.github.siloverse.messaging.spring.topology.TopologyDeclaration
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import tools.jackson.databind.ObjectMapper

@Configuration
@Import(AsyncMessagingConfiguration::class, MessagingConfiguration::class)
class BrokerageMessagingConfiguration {

    @Bean
    fun rabbitMqConnectionSettings(environment: Environment): RabbitMqConnectionSettings =
        Binder.get(environment)
            .bind("messaging.rabbitmq", RabbitMqConnectionSettings::class.java)
            .orElseThrow { IllegalStateException("Missing messaging.rabbitmq configuration") }

    @Bean(destroyMethod = "close")
    fun rabbitConnection(settings: RabbitMqConnectionSettings): Connection =
        RabbitMqConnector.connect(settings)

    @Bean
    fun messageTransport(connection: Connection): MessageTransport =
        RabbitMqMessageTransport(connection)

    @Bean
    fun payloadSerializer(mapper: ObjectMapper): PayloadSerializer =
        JacksonPayloadSerializer(mapper)

    @Bean
    fun messageNames(): MessageNameRegistry =
        MessageNameRegistry.compose(LedgerMessages.names())

    @Bean
    fun retrySettings(): RetrySettings = RetrySettings.DEFAULT

    @Bean
    fun failedDeliveryHandler(retrySettings: RetrySettings): FailedDeliveryHandler =
        RetryingFailedDeliveryHandler(retrySettings, SERVICE_NAME)

    @Bean
    fun rabbitTopology(
        connection: Connection,
        consumers: ConsumerRegistry,
        names: MessageNameRegistry,
        retrySettings: RetrySettings,
    ): TopologyDeclaration {
        val declarer = RabbitMqTopologyDeclarer(connection)
        return TopologyDeclaration {
            declarer.declarePublisherTopology(names)
            declarer.declareConsumerTopology(SERVICE_NAME, consumers, names, retrySettings)
        }
    }

    @Bean
    fun rabbitListener(
        settings: RabbitMqConnectionSettings,
        consumers: ConsumerRegistry,
        names: MessageNameRegistry,
        mapper: ObjectMapper,
        dispatcher: MessageDispatcher,
        failedDeliveryHandler: FailedDeliveryHandler,
    ): MessageListener =
        BrokerageRabbitListener(settings, SERVICE_NAME, consumers, names, mapper, dispatcher, failedDeliveryHandler)

    companion object {
        const val SERVICE_NAME = "brokerage"
    }
}