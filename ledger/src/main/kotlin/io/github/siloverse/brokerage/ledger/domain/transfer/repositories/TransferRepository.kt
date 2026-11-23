package io.github.siloverse.brokerage.ledger.domain.transfer.repositories

import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import java.util.UUID

interface TransferRepository {
    fun insert(uuid: UUID, kind: TransferKind, idempotencyKey: UUID, holdId: Long? = null): Long?
    fun findUuidByKey(idempotencyKey: UUID): UUID
}