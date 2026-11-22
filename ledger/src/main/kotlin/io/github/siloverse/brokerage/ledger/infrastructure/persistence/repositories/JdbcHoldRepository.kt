package io.github.siloverse.brokerage.ledger.infrastructure.persistence.repositories

import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcHoldRepository(@Qualifier("ledgerJdbc") private val jdbc: JdbcClient) : HoldRepository {

    override fun insert(uuid: UUID, accountId: Long, reference: UUID, amount: Long): Long? {
        return jdbc.sql(
            """
               insert into holds (uuid, account_id, reference, amount) 
               values (:uuid, :account_id, :reference, :amount)
               on conflict on constraint holds_reference_once do nothing
               returning id
            """.trimIndent()
        )
            .param("uuid", uuid)
            .param("account_id", accountId)
            .param("reference", reference)
            .param("amount", amount)
            .query(Long::class.java)
            .optional()
            .orElse(null)
    }

    override fun findUuidByReference(reference: UUID): UUID {
        return jdbc.sql(
            """
               select uuid
               from holds
               where reference=:reference
            """.trimIndent()
        )
            .param("reference", reference)
            .query(UUID::class.java)
            .single()
    }
}