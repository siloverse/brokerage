package io.github.siloverse.brokerage.ledger

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.postgresql.PostgreSQLContainer

/** The ledger module started on its own, for integration tests. The real application is `app`. */
@SpringBootApplication
class LedgerTestApplication
