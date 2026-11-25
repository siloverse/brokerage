package io.github.siloverse.brokerage.ledger.infrastructure.web.model

import io.github.siloverse.brokerage.ledger.domain.check.model.LedgerCheckReport
import java.util.UUID

data class LedgerCheckResponse(
    val healthy: Boolean,
    val unbalancedTransfers: List<UnbalancedTransferResponse>,
    val bookedMismatches: List<BookedMismatchResponse>,
    val reservedMismatches: List<ReservedMismatchResponse>,
) {
    data class UnbalancedTransferResponse(val transferUuid: UUID, val assetType: String, val assetCode: String, val sum: Long)

    data class BookedMismatchResponse(val accountUuid: UUID, val booked: Long, val entriesSum: Long)

    data class ReservedMismatchResponse(val accountUuid: UUID, val reserved: Long, val openHolds: Long)

    companion object {
        fun from(report: LedgerCheckReport) = LedgerCheckResponse(
            healthy = report.healthy,
            unbalancedTransfers = report.unbalancedTransfers.map {
                UnbalancedTransferResponse(it.transferUuid, it.assetType.name, it.assetCode, it.sum)
            },
            bookedMismatches = report.bookedMismatches.map { BookedMismatchResponse(it.accountUuid, it.booked, it.entriesSum) },
            reservedMismatches = report.reservedMismatches.map { ReservedMismatchResponse(it.accountUuid, it.reserved, it.openHolds) },
        )
    }
}
