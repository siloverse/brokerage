package io.github.siloverse.brokerage.ledger.infrastructure.persistence.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.transaction.annotation.Isolation

@ConfigurationProperties("brokerage.ledger.datasource")
data class LedgerDataSourceProperties(
    val url: String,
    val username: String,
    val password: String,
    val poolSize: Int = 3,
    val connectionTimeoutMillis: Long = 3_000,
    val lockTimeoutMillis: Long = 2_000,
    val isolation: Isolation = Isolation.READ_COMMITTED,
) {
    /** The level in the words the JDBC driver and the pool use, or null to leave the database's own default. */
    val jdbcIsolation: String? = if (isolation == Isolation.DEFAULT) null else "TRANSACTION_${isolation.name}"
}
