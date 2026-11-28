package io.github.siloverse.brokerage.ledger.infrastructure.web.retry

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.infrastructure.web.controllers.DepositController
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.aopalliance.intercept.MethodInvocation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.dao.CannotAcquireLockException
import org.springframework.resilience.retry.MethodRetryEvent
import java.util.function.Consumer

class TransactionRetryListenerTest : BaseUnitTest() {

    @Mock
    lateinit var invocation: MethodInvocation

    @Mock
    lateinit var registryProvider: ObjectProvider<MeterRegistry>

    private val registry = SimpleMeterRegistry()

    private fun aLostDeposit(aborted: Boolean): MethodRetryEvent {
        whenever(invocation.method).thenReturn(DepositController::class.java.methods.first { it.name == "deposit" })
        whenever(registryProvider.ifAvailable(any())).doAnswer { it.getArgument<Consumer<MeterRegistry>>(0).accept(registry) }
        return MethodRetryEvent(invocation, CannotAcquireLockException("could not serialize access"), aborted)
    }

    private fun count(outcome: String): Double =
        registry.find("ledger.transaction.retries").tags("operation", "deposit", "outcome", outcome).counter()?.count() ?: 0.0

    @Test
    fun `an attempt that is run again is counted as retried`() {
        val listener = TransactionRetryListener(registryProvider)

        listener.onRetry(aLostDeposit(aborted = false))
        listener.onRetry(aLostDeposit(aborted = false))

        assertEquals(2.0, count("retried"))
        assertEquals(0.0, count("given_up"))
    }

    @Test
    fun `the attempt after which the retries are used up is counted as given up`() {
        TransactionRetryListener(registryProvider).onRetry(aLostDeposit(aborted = true))

        assertEquals(1.0, count("given_up"))
        assertEquals(0.0, count("retried"))
    }

    @Test
    fun `a failure that is not a lost transaction is not counted`() {
        val refused = MethodRetryEvent(invocation, IllegalArgumentException("amount must be positive"), true)

        TransactionRetryListener(registryProvider).onRetry(refused)

        assertEquals(0.0, count("given_up"))
        assertEquals(0.0, count("retried"))
    }
}
