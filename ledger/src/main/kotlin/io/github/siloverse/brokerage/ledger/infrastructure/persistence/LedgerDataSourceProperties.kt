package io.github.siloverse.brokerage.ledger.infrastructure.persistence.settings

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties("brokerage.ledger.datasource")
data class LedgerDataSourceProperties(
    val url: String,
    val username: String,
    val password: String,
    val poolSize: Int = 3,
)