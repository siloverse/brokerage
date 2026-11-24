package io.github.siloverse.brokerage.ledger.application.fill.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.application.fill.model.FillInstruction
import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.exception.HoldFillException
import io.github.siloverse.brokerage.ledger.domain.hold.model.UserHold
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import io.github.siloverse.brokerage.ledger.domain.transfer.strategy.BuyFillStrategy
import io.github.siloverse.brokerage.ledger.domain.transfer.strategy.SellFillStrategy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class FillServiceTest : BaseUnitTest() {

    @Mock
    lateinit var holdRepository: HoldRepository

    @Mock
    lateinit var transferRepository: TransferRepository

    @Mock
    lateinit var accountRepository: AccountRepository

    @Mock
    lateinit var ledgerEntryRepository: LedgerEntryRepository

    lateinit var service: FillService

    @BeforeEach
    fun createTheContextWithItsRealStrategies() {
        val transferInvoker = TransferInvoker(ledgerEntryRepository)
        service = FillService(
            holdRepository,
            transferRepository,
            listOf(
                BuyFillStrategy(holdRepository, accountRepository, transferInvoker),
                SellFillStrategy(holdRepository, accountRepository, transferInvoker),
            )
        )
    }

    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val reference = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val execution = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    private val holdUuid = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
    private val instruction = FillInstruction(execution, reference, "ACME", quantity = 4, currency = Currency.EUR, amount = 20_000, fee = 200)

    private val holdId = 7L
    private val transferId = 1L
    private val userCash = 10L
    private val settlementCash = 20L
    private val userPosition = 30L
    private val venuePosition = 40L
    private val feeIncome = 50L

    private fun aHold() {
        whenever(holdRepository.findUserHoldByReference(reference))
            .thenReturn(UserHold(holdId, holdUuid, userCash, user, "EUR", AssetType.CASH))
    }

    private fun aNewExecution() {
        aHold()
        whenever(transferRepository.insert(any(), eq(TransferKind.FILL), eq(execution), eq(holdId))).thenReturn(transferId)
    }

    private fun account(id: Long) = AccountIdentifier(id, UUID.randomUUID())

    private fun theAccounts() {
        whenever(accountRepository.ensureAccount(AccountType.USER, AssetType.CASH, "EUR", user)).thenReturn(account(userCash))
        whenever(accountRepository.ensureAccount(AccountType.USER, AssetType.POSITION, "ACME", user)).thenReturn(account(userPosition))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.CASH, "EUR", null)).thenReturn(account(settlementCash))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.POSITION, "ACME", null)).thenReturn(account(venuePosition))
        whenever(accountRepository.ensureAccount(AccountType.FEE_INCOME, AssetType.CASH, "EUR", null)).thenReturn(account(feeIncome))
    }

    private fun aFillThatFits(total: Long = 20_200) {
        aNewExecution()
        whenever(holdRepository.consumeHold(holdId, total)).thenReturn(1)
        theAccounts()
    }

    @Test
    fun `a fill writes six entries, the price, the fee and the shares each as a pair that sums to zero`() {
        aFillThatFits()

        service.transfer(instruction)

        verify(ledgerEntryRepository).insert(transferId, userCash, -20_000)
        verify(ledgerEntryRepository).insert(transferId, settlementCash, 20_000)
        verify(ledgerEntryRepository).insert(transferId, userCash, -200)
        verify(ledgerEntryRepository).insert(transferId, feeIncome, 200)
        verify(ledgerEntryRepository).insert(transferId, venuePosition, -4)
        verify(ledgerEntryRepository).insert(transferId, userPosition, 4)
        verifyNoMoreInteractions(ledgerEntryRepository)
    }

    @Test
    fun `a fill moves each balance by the sum of that account's entries`() {
        aFillThatFits()

        service.transfer(instruction)

        verify(accountRepository).settleReserved(userCash, 20_000)
        verify(accountRepository).settleReserved(userCash, 200)
        verify(accountRepository).addToBooked(settlementCash, 20_000)
        verify(accountRepository).addToBooked(feeIncome, 200)
        verify(accountRepository).removeFromBooked(venuePosition, 4)
        verify(accountRepository).addToBooked(userPosition, 4)
    }

    @Test
    fun `the user's cash leaves through the reservation, never through a plain balance change`() {
        aFillThatFits()

        service.transfer(instruction)

        verify(accountRepository, never()).addToBooked(eq(userCash), any())
        verify(accountRepository, never()).removeFromBooked(eq(userCash), any())
        verify(accountRepository, never()).takeFromAvailable(any(), any())
    }

    @Test
    fun `the hold is consumed by the price plus the fee`() {
        aFillThatFits()

        service.transfer(instruction)

        verify(holdRepository).consumeHold(holdId, 20_200)
    }

    @Test
    fun `the transfer carries the hold and its uuid is returned`() {
        aFillThatFits()

        val receipt = service.transfer(instruction)

        val writtenUuid = argumentCaptor<UUID>()
        verify(transferRepository).insert(writtenUuid.capture(), eq(TransferKind.FILL), eq(execution), eq(holdId))
        assertFalse(receipt.replayed)
        assertEquals(writtenUuid.firstValue, receipt.transferUuid)
    }

    @Test
    fun `a fee of zero writes no fee entries`() {
        aFillThatFits(total = 20_000)

        service.transfer(instruction.copy(fee = 0))

        verify(ledgerEntryRepository).insert(transferId, userCash, -20_000)
        verify(ledgerEntryRepository).insert(transferId, settlementCash, 20_000)
        verify(ledgerEntryRepository).insert(transferId, venuePosition, -4)
        verify(ledgerEntryRepository).insert(transferId, userPosition, 4)
        verifyNoMoreInteractions(ledgerEntryRepository)
        verify(accountRepository).settleReserved(userCash, 20_000)
    }

    @Test
    fun `the hold is consumed before any account, and the accounts change in ascending id order`() {
        aFillThatFits()

        service.transfer(instruction)

        val order = inOrder(transferRepository, holdRepository, accountRepository)
        order.verify(transferRepository).insert(any(), any(), any(), any())
        order.verify(holdRepository).consumeHold(holdId, 20_200)
        order.verify(accountRepository).settleReserved(userCash, 20_000)
        order.verify(accountRepository).settleReserved(userCash, 200)
        order.verify(accountRepository).addToBooked(settlementCash, 20_000)
        order.verify(accountRepository).addToBooked(userPosition, 4)
        order.verify(accountRepository).removeFromBooked(venuePosition, 4)
        order.verify(accountRepository).addToBooked(feeIncome, 200)
    }

    @Test
    fun `a hold with too little left refuses the fill and changes nothing else`() {
        aNewExecution()
        whenever(holdRepository.consumeHold(holdId, 20_200)).thenReturn(0)

        val error = assertThrows<HoldFillException> { service.transfer(instruction) }

        assertTrue(error.message!!.contains(holdUuid.toString()))
        assertTrue(error.message!!.contains("20200"))
        verifyNoInteractions(accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `an unknown reference is refused before anything is written`() {
        whenever(holdRepository.findUserHoldByReference(reference)).thenReturn(null)

        val error = assertThrows<HoldNotFoundException> { service.transfer(instruction) }

        assertTrue(error.message!!.contains(reference.toString()))
        verifyNoInteractions(transferRepository, accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `an execution seen before returns the first transfer and consumes nothing`() {
        val firstTransferUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        aHold()
        whenever(transferRepository.insert(any(), eq(TransferKind.FILL), eq(execution), eq(holdId))).thenReturn(null)
        whenever(transferRepository.findUuidByKey(execution)).thenReturn(firstTransferUuid)

        val receipt = service.transfer(instruction)

        assertTrue(receipt.replayed)
        assertEquals(firstTransferUuid, receipt.transferUuid)
        verify(holdRepository, never()).consumeHold(any(), any())
        verifyNoInteractions(accountRepository, ledgerEntryRepository)
    }

    private fun aSellHold(instrument: String = "ACME") {
        whenever(holdRepository.findUserHoldByReference(reference))
            .thenReturn(UserHold(holdId, holdUuid, userPosition, user, instrument, AssetType.POSITION))
    }

    private fun aSellThatFits() {
        aSellHold()
        whenever(transferRepository.insert(any(), eq(TransferKind.FILL), eq(execution), eq(holdId))).thenReturn(transferId)
        whenever(holdRepository.consumeHold(holdId, 4)).thenReturn(1)
        theAccounts()
    }

    @Test
    fun `a sell writes six entries, the shares out, the price in, and the fee out of the proceeds`() {
        aSellThatFits()

        service.transfer(instruction)

        verify(ledgerEntryRepository).insert(transferId, userPosition, -4)
        verify(ledgerEntryRepository).insert(transferId, venuePosition, 4)
        verify(ledgerEntryRepository).insert(transferId, settlementCash, -20_000)
        verify(ledgerEntryRepository).insert(transferId, userCash, 20_000)
        verify(ledgerEntryRepository).insert(transferId, userCash, -200)
        verify(ledgerEntryRepository).insert(transferId, feeIncome, 200)
        verifyNoMoreInteractions(ledgerEntryRepository)
    }

    @Test
    fun `a sell moves each balance by the sum of that account's entries`() {
        aSellThatFits()

        service.transfer(instruction)

        verify(accountRepository).settleReserved(userPosition, 4)
        verify(accountRepository).addToBooked(venuePosition, 4)
        verify(accountRepository).removeFromBooked(settlementCash, 20_000)
        verify(accountRepository).addToBooked(userCash, 20_000)
        verify(accountRepository).removeFromBooked(userCash, 200)
        verify(accountRepository).addToBooked(feeIncome, 200)
    }

    @Test
    fun `a sell consumes the hold by the number of shares, not by the money`() {
        aSellThatFits()

        service.transfer(instruction)

        verify(holdRepository).consumeHold(holdId, 4)
    }

    @Test
    fun `in a sell the shares leave through the reservation and the cash is never reserved`() {
        aSellThatFits()

        service.transfer(instruction)

        verify(accountRepository, never()).settleReserved(eq(userCash), any())
        verify(accountRepository, never()).removeFromBooked(eq(userPosition), any())
        verify(accountRepository, never()).addToBooked(eq(userPosition), any())
    }

    @Test
    fun `a sell consumes the hold before any account, and the accounts change in ascending id order`() {
        aSellThatFits()

        service.transfer(instruction)

        val order = inOrder(transferRepository, holdRepository, accountRepository)
        order.verify(transferRepository).insert(any(), any(), any(), any())
        order.verify(holdRepository).consumeHold(holdId, 4)
        order.verify(accountRepository).addToBooked(userCash, 20_000)
        order.verify(accountRepository).removeFromBooked(userCash, 200)
        order.verify(accountRepository).removeFromBooked(settlementCash, 20_000)
        order.verify(accountRepository).settleReserved(userPosition, 4)
        order.verify(accountRepository).addToBooked(venuePosition, 4)
        order.verify(accountRepository).addToBooked(feeIncome, 200)
    }

    @Test
    fun `a share hold with too few shares left refuses the sell and changes nothing else`() {
        aSellHold()
        whenever(transferRepository.insert(any(), eq(TransferKind.FILL), eq(execution), eq(holdId))).thenReturn(transferId)
        whenever(holdRepository.consumeHold(holdId, 4)).thenReturn(0)

        assertThrows<HoldFillException> { service.transfer(instruction) }

        verifyNoInteractions(accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `a fill for another instrument than the share hold is refused before anything is written`() {
        aSellHold(instrument = "OTHER")

        assertThrows<IllegalArgumentException> { service.transfer(instruction) }

        verifyNoInteractions(transferRepository, accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `a fill in another currency than the cash hold is refused before anything is written`() {
        whenever(holdRepository.findUserHoldByReference(reference))
            .thenReturn(UserHold(holdId, holdUuid, userCash, user, "USD", AssetType.CASH))

        assertThrows<IllegalArgumentException> { service.transfer(instruction) }

        verifyNoInteractions(transferRepository, accountRepository, ledgerEntryRepository)
    }
}
