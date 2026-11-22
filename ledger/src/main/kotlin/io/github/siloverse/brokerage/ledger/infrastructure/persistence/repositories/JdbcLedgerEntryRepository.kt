package io.github.siloverse.brokerage.ledger.infrastructure.persistence.repositories

import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcLedgerEntryRepository(@Qualifier("ledgerJdbc") private val jdbc: JdbcClient) : LedgerEntryRepository {

    override fun insert(transferId: Long, accountId: Long, amount: Long) {
        jdbc.sql(
            """
                insert into ledger_entries (transfer_id, account_id, amount)
                values (:transfer_id, :account_id, :amount)
            """.trimIndent()
        )
            .param("transfer_id", transferId)
            .param("account_id", accountId)
            .param("amount", amount)
            .update()
    }
}
