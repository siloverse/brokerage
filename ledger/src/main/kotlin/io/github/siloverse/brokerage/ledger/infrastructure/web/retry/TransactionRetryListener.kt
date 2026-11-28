package io.github.siloverse.brokerage.ledger.infrastructure.web.retry

import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.event.EventListener
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.resilience.retry.MethodRetryEvent
import org.springframework.stereotype.Component

@Component
class TransactionRetryListener(private val meterRegistry: ObjectProvider<MeterRegistry>) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onRetry(event: MethodRetryEvent) {
        if (event.failure !is PessimisticLockingFailureException) return
        val outcome = if (event.isRetryAborted) "given_up" else "retried"
        val operation = event.method.name
        logger.warn("ledger transaction lost: operation={} outcome={} cause={}", operation, outcome, event.failure.javaClass.simpleName)
        meterRegistry.ifAvailable { it.counter("ledger.transaction.retries", "operation", operation, "outcome", outcome).increment() }
    }
}
