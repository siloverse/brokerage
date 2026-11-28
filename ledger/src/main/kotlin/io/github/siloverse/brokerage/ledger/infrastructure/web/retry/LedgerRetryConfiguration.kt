package io.github.siloverse.brokerage.ledger.infrastructure.web.retry

import org.springframework.context.annotation.Configuration
import org.springframework.resilience.annotation.EnableResilientMethods

@Configuration
@EnableResilientMethods
class LedgerRetryConfiguration
