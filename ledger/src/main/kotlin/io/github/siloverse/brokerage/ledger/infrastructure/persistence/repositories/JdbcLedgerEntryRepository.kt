package io.github.siloverse.brokerage.ledger.infrastructure.persistence.repositories

import io.github.siloverse.brokerage.ledger.domain.account.model.UnbalancedAsset
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
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

    override fun findUnbalancedAssets(transferId: Long): List<UnbalancedAsset> {
        return jdbc.sql(
            """
                select a.asset_type, a.asset_code, sum(e.amount) as sum
                from ledger_entries e
                join accounts a on a.id = e.account_id
                where e.transfer_id = :transfer_id
                group by a.asset_type, a.asset_code
                having sum(e.amount) <> 0
            """.trimIndent()
        )
            .param("transfer_id", transferId)
            .query(UnbalancedAsset::class.java)
            .list()
            .requireNoNulls()
    }


}
