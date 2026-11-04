package io.github.siloverse.brokerage.ledger.infrastructure.persistence

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.siloverse.brokerage.ledger.infrastructure.persistence.settings.LedgerDataSourceProperties
import org.flywaydb.core.Flyway
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.PlatformTransactionManager
import javax.sql.DataSource

@Configuration
@EnableConfigurationProperties(LedgerDataSourceProperties::class)
class LedgerPersistenceConfiguration {

    @Bean
    fun ledgerDataSource(ledgerDataSourceProperties: LedgerDataSourceProperties): DataSource {
        return HikariDataSource(HikariConfig().apply {
            jdbcUrl = ledgerDataSourceProperties.url
            username = ledgerDataSourceProperties.username
            password = ledgerDataSourceProperties.password
            maximumPoolSize = ledgerDataSourceProperties.poolSize
            poolName = "ledger"
            schema = "ledger"
        })
    }

    @Bean
    fun ledgerJdbc(@Qualifier("ledgerDataSource") ds: DataSource): JdbcClient {
        return JdbcClient.create(ds)
    }

    @Bean
    fun ledgerTransactionManager(@Qualifier("ledgerDataSource") ds: DataSource): PlatformTransactionManager {
        return JdbcTransactionManager(ds)
    }

    @Bean(initMethod = "migrate")
    fun ledgerFlyway(@Qualifier("ledgerDataSource") ds: DataSource): Flyway {
        return Flyway
            .configure()
            .dataSource(ds)
            .schemas("ledger")
            .locations("classpath:db/migration/ledger").load()
    }
}