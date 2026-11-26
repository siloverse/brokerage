package io.github.siloverse.brokerage.ledger.infrastructure.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("brokerage.ledger.datasource")
data class LedgerDataSourceProperties(
    val url: String,
    val username: String,
    val password: String,
    val poolSize: Int = 3,
    val connectionTimeoutMillis: Long = 3_000,
    val lockTimeoutMillis: Long = 2_000,
)