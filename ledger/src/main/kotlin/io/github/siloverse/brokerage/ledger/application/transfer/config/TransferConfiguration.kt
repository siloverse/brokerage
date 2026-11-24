package io.github.siloverse.brokerage.ledger.application.transfer.config

import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TransferConfiguration {

    @Bean
    fun transferInvoker(ledgerEntryRepository: LedgerEntryRepository) =
        TransferInvoker(ledgerEntryRepository)
}