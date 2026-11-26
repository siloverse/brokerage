package io.github.siloverse.brokerage.app.consumers

import com.rabbitmq.client.Connection
import io.github.siloverse.messaging.core.consumer.ConsumerRegistry
import io.github.siloverse.messaging.core.dispatch.MessageDispatcher
import io.github.siloverse.messaging.core.naming.MessageNameRegistry
import io.github.siloverse.messaging.rabbitmq.connection.RabbitMqConnectionSettings
import io.github.siloverse.messaging.rabbitmq.connection.RabbitMqConnector
import io.github.siloverse.messaging.rabbitmq.listener.RabbitMqMessageListener
import io.github.siloverse.messaging.rabbitmq.retry.FailedDeliveryHandler
import io.github.siloverse.messaging.spring.listener.MessageListener
import io.github.siloverse.messaging.spring.serialization.JacksonPayloadDeserializer
import tools.jackson.databind.ObjectMapper

class BrokerageRabbitListener(
    private val settings: RabbitMqConnectionSettings,
    private val serviceName: String,
    private val consumers: ConsumerRegistry,
    private val names: MessageNameRegistry,
    private val mapper: ObjectMapper,
    private val dispatcher: MessageDispatcher,
    private val failedDeliveryHandler: FailedDeliveryHandler,
) : MessageListener {

    private var consumeConnection: Connection? = null
    private var listener: RabbitMqMessageListener? = null

    override fun start() {
        consumeConnection = RabbitMqConnector.connect(settings)
        listener = RabbitMqMessageListener(
            consumeConnection, serviceName, consumers, names,
            JacksonPayloadDeserializer(mapper), dispatcher, failedDeliveryHandler,
        ).also { it.start() }
    }

    override fun stop() {
        listener?.stop()
        runCatching { consumeConnection?.close() }
    }
}