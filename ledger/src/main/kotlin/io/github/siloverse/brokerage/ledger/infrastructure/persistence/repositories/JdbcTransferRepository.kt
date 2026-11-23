package io.github.siloverse.brokerage.ledger.infrastructure.persistence.repositories

import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcTransferRepository(@Qualifier("ledgerJdbc") private val jdbc: JdbcClient) : TransferRepository {
    override fun insert(uuid: UUID, kind: TransferKind, idempotencyKey: UUID, holdId: Long?): Long? {
        return jdbc.sql(
            """
               insert into transfers (uuid, kind, idempotency_key, hold_id) 
               values (:uuid, :kind, :idempotency_key, :hold_id)
               on conflict on constraint transfers_key_once do nothing
               returning id
            """.trimIndent()
        )
            .param("uuid", uuid)
            .param("kind", kind.name)
            .param("idempotency_key", idempotencyKey)
            .param("hold_id", holdId)
            .query(Long::class.java)
            .optional()
            .orElse(null)
    }

    override fun findUuidByKey(idempotencyKey: UUID): UUID {
        return jdbc.sql(
            """
               select uuid
               from transfers
               where idempotency_key=:idempotency_key
            """.trimIndent()
        )
            .param("idempotency_key", idempotencyKey)
            .query(UUID::class.java)
            .single()
    }
}