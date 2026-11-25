package io.github.siloverse.brokerage.ledger.infrastructure.web.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.check.model.BookedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.LedgerCheckReport
import io.github.siloverse.brokerage.ledger.domain.check.model.ReservedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.UnbalancedTransfer
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.LedgerCheckResponse.BookedMismatchResponse
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.LedgerCheckResponse.ReservedMismatchResponse
import io.github.siloverse.brokerage.ledger.infrastructure.web.model.LedgerCheckResponse.UnbalancedTransferResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

class LedgerCheckResponseTest {

    private val transferUuid = UUID.randomUUID()
    private val accountUuid = UUID.randomUUID()

    @Test
    fun `a healthy report becomes a healthy response with three empty lists`() {
        val response = LedgerCheckResponse.from(LedgerCheckReport(emptyList(), emptyList(), emptyList()))

        assertEquals(LedgerCheckResponse(true, emptyList(), emptyList(), emptyList()), response)
    }

    @Test
    fun `every finding of the report is copied into the response`() {
        val report = LedgerCheckReport(
            unbalancedTransfers = listOf(UnbalancedTransfer(transferUuid, AssetType.POSITION, "ACME", -4)),
            bookedMismatches = listOf(BookedMismatch(accountUuid, booked = 7, entriesSum = 0)),
            reservedMismatches = listOf(ReservedMismatch(accountUuid, reserved = 30, openHolds = 50)),
        )

        val response = LedgerCheckResponse.from(report)

        assertEquals(
            LedgerCheckResponse(
                healthy = false,
                unbalancedTransfers = listOf(UnbalancedTransferResponse(transferUuid, "POSITION", "ACME", -4)),
                bookedMismatches = listOf(BookedMismatchResponse(accountUuid, 7, 0)),
                reservedMismatches = listOf(ReservedMismatchResponse(accountUuid, 30, 50)),
            ),
            response,
        )
    }
}
