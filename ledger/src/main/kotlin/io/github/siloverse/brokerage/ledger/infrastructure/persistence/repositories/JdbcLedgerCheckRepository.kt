package io.github.siloverse.brokerage.ledger.infrastructure.persistence.repositories

import io.github.siloverse.brokerage.ledger.domain.check.model.BookedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.ReservedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.UnbalancedTransfer
import io.github.siloverse.brokerage.ledger.domain.check.repositories.LedgerCheckRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository

@Repository
class JdbcLedgerCheckRepository(@Qualifier("ledgerJdbc") private val jdbc: JdbcClient) : LedgerCheckRepository {

    override fun findUnbalancedTransfers(): List<UnbalancedTransfer> =
        jdbc.sql(
            """
                select t.uuid as transfer_uuid, a.asset_type, a.asset_code, sum(e.amount) as sum
                from ledger_entries e
                join accounts a on a.id = e.account_id
                join transfers t on t.id = e.transfer_id
                group by t.uuid, a.asset_type, a.asset_code
                having sum(e.amount) <> 0
            """.trimIndent()
        ).query(UnbalancedTransfer::class.java).list().requireNoNulls()

    override fun findBookedMismatches(): List<BookedMismatch> =
        jdbc.sql(
            """
                select a.uuid as account_uuid, a.booked, coalesce(sum(e.amount), 0) as entries_sum
                from accounts a
                left join ledger_entries e on e.account_id = a.id
                group by a.id
                having a.booked <> coalesce(sum(e.amount), 0)
            """.trimIndent()
        ).query(BookedMismatch::class.java).list().requireNoNulls()

    override fun findReservedMismatches(): List<ReservedMismatch> =
        jdbc.sql(
            """
                select a.uuid as account_uuid, a.reserved, coalesce(sum(h.amount - h.consumed - h.released), 0) as open_holds
                from accounts a
                left join holds h on h.account_id = a.id
                group by a.id
                having a.reserved <> coalesce(sum(h.amount - h.consumed - h.released), 0)
            """.trimIndent()
        ).query(ReservedMismatch::class.java).list().requireNoNulls()
}
