package io.github.siloverse.brokerage.ledger.infrastructure.web.retry

import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.resilience.annotation.Retryable

@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
@Retryable(
    includes = [PessimisticLockingFailureException::class],
    maxRetries = 3,
    delay = 5,
    jitter = 5,
    multiplier = 2.0,
    maxDelay = 50,
)
annotation class RetryLostTransaction
