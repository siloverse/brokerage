package io.github.siloverse.brokerage.ledger.application.check.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.check.model.BookedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.ReservedMismatch
import io.github.siloverse.brokerage.ledger.domain.check.model.UnbalancedTransfer
import io.github.siloverse.brokerage.ledger.domain.check.repositories.LedgerCheckRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.kotlin.whenever
import java.util.UUID

class LedgerCheckServiceTest : BaseUnitTest() {

    @Mock
    lateinit var ledgerCheckRepository: LedgerCheckRepository

    @InjectMocks
    lateinit var service: LedgerCheckService

    private val unbalancedTransfer = UnbalancedTransfer(UUID.randomUUID(), AssetType.CASH, "EUR", 100)
    private val bookedMismatch = BookedMismatch(UUID.randomUUID(), booked = 7, entriesSum = 0)
    private val reservedMismatch = ReservedMismatch(UUID.randomUUID(), reserved = 30, openHolds = 0)

    @Test
    fun `a ledger with nothing wrong is healthy`() {
        val report = service.check()

        assertTrue(report.healthy)
    }

    @Test
    fun `the report carries what each of the three checks found`() {
        whenever(ledgerCheckRepository.findUnbalancedTransfers()).thenReturn(listOf(unbalancedTransfer))
        whenever(ledgerCheckRepository.findBookedMismatches()).thenReturn(listOf(bookedMismatch))
        whenever(ledgerCheckRepository.findReservedMismatches()).thenReturn(listOf(reservedMismatch))

        val report = service.check()

        assertEquals(listOf(unbalancedTransfer), report.unbalancedTransfers)
        assertEquals(listOf(bookedMismatch), report.bookedMismatches)
        assertEquals(listOf(reservedMismatch), report.reservedMismatches)
    }

    @Test
    fun `one unbalanced transfer is enough to be unhealthy`() {
        whenever(ledgerCheckRepository.findUnbalancedTransfers()).thenReturn(listOf(unbalancedTransfer))

        assertFalse(service.check().healthy)
    }

    @Test
    fun `one wrong booked balance is enough to be unhealthy`() {
        whenever(ledgerCheckRepository.findBookedMismatches()).thenReturn(listOf(bookedMismatch))

        assertFalse(service.check().healthy)
    }

    @Test
    fun `one wrong reservation is enough to be unhealthy`() {
        whenever(ledgerCheckRepository.findReservedMismatches()).thenReturn(listOf(reservedMismatch))

        assertFalse(service.check().healthy)
    }
}
