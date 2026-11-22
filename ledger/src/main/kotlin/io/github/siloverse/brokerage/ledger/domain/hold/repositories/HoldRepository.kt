package io.github.siloverse.brokerage.ledger.domain.hold.repositories

import java.util.UUID

interface HoldRepository {
    fun insert(uuid: UUID, accountId: Long, reference: UUID, amount: Long): Long?
    fun findUuidByReference(reference: UUID): UUID
}