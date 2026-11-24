package io.github.siloverse.brokerage.ledger.application.transfer.config

import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import io.github.siloverse.brokerage.ledger.domain.transfer.strategy.BuyFillStrategy
import io.github.siloverse.brokerage.ledger.domain.transfer.strategy.FillStrategy
import io.github.siloverse.brokerage.ledger.domain.transfer.strategy.SellFillStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TransferConfiguration {

    @Bean
    fun transferInvoker(ledgerEntryRepository: LedgerEntryRepository) =
        TransferInvoker(ledgerEntryRepository)

    @Bean
    fun buyFillStrategy(
        holdRepository: HoldRepository,
        accountRepository: AccountRepository,
        transferInvoker: TransferInvoker
    ): FillStrategy = BuyFillStrategy(holdRepository, accountRepository, transferInvoker)

    @Bean
    fun sellFillStrategy(
        holdRepository: HoldRepository,
        accountRepository: AccountRepository,
        transferInvoker: TransferInvoker
    ): FillStrategy = SellFillStrategy(holdRepository, accountRepository, transferInvoker)
}