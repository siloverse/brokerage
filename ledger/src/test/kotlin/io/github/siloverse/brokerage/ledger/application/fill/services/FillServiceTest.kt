package io.github.siloverse.brokerage.ledger.application.fill.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.application.fill.model.FillInstruction
import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
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
import org.mockito.kotlin.whenever
import java.util.UUID

/** The context: finding the hold, choosing the strategy, the gate. The bookings are in FillStrategiesTest. */
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

    private fun aCashHold(currency: String = "EUR") {
        whenever(holdRepository.findUserHoldByReference(reference))
            .thenReturn(UserHold(holdId, holdUuid, userCash, user, currency, AssetType.CASH))
    }

    private fun aShareHold(instrument: String = "ACME") {
        whenever(holdRepository.findUserHoldByReference(reference))
            .thenReturn(UserHold(holdId, holdUuid, userPosition, user, instrument, AssetType.POSITION))
    }

    private fun aNewExecution() {
        whenever(transferRepository.insert(any(), eq(TransferKind.FILL), eq(execution), eq(holdId))).thenReturn(transferId)
    }

    private fun account(id: Long) = AccountIdentifier(id, UUID.randomUUID())

    private fun aBuyTheHoldCovers() {
        aCashHold()
        aNewExecution()
        whenever(holdRepository.lockRest(holdId)).thenReturn(50_500)
        whenever(accountRepository.ensureAccount(AccountType.USER, AssetType.POSITION, "ACME", user)).thenReturn(account(userPosition))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.POSITION, "ACME", null)).thenReturn(account(venuePosition))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.CASH, "EUR", null)).thenReturn(account(settlementCash))
        whenever(accountRepository.ensureAccount(AccountType.FEE_INCOME, AssetType.CASH, "EUR", null)).thenReturn(account(feeIncome))
        whenever(accountRepository.lockAvailable(any())).thenReturn(mapOf(userCash to 49_500L))
        whenever(holdRepository.consumeHold(holdId, 20_200)).thenReturn(1)
    }

    private fun aSellTheHoldCovers() {
        aShareHold()
        aNewExecution()
        whenever(holdRepository.lockRest(holdId)).thenReturn(6)
        whenever(accountRepository.ensureAccount(AccountType.USER, AssetType.CASH, "EUR", user)).thenReturn(account(userCash))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.POSITION, "ACME", null)).thenReturn(account(venuePosition))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.CASH, "EUR", null)).thenReturn(account(settlementCash))
        whenever(accountRepository.ensureAccount(AccountType.FEE_INCOME, AssetType.CASH, "EUR", null)).thenReturn(account(feeIncome))
        whenever(accountRepository.lockAvailable(any())).thenReturn(mapOf(userPosition to 4L))
        whenever(holdRepository.consumeHold(holdId, 4)).thenReturn(1)
    }

    @Test
    fun `a hold on cash is booked as a buy`() {
        aBuyTheHoldCovers()

        service.transfer(instruction)

        verify(accountRepository).settleReserved(userCash, 20_200)
        verify(accountRepository).addToBooked(userPosition, 4)
    }

    @Test
    fun `a hold on shares is booked as a sell`() {
        aSellTheHoldCovers()

        service.transfer(instruction)

        verify(accountRepository).settleReserved(userPosition, 4)
        verify(accountRepository).addToBooked(userCash, 20_000)
    }

    @Test
    fun `the transfer carries the hold and its uuid is returned`() {
        aBuyTheHoldCovers()

        val receipt = service.transfer(instruction)

        val writtenUuid = argumentCaptor<UUID>()
        verify(transferRepository).insert(writtenUuid.capture(), eq(TransferKind.FILL), eq(execution), eq(holdId))
        assertFalse(receipt.replayed)
        assertEquals(writtenUuid.firstValue, receipt.transferUuid)
    }

    @Test
    fun `the transfer is written before the hold is locked or any account is touched`() {
        aBuyTheHoldCovers()

        service.transfer(instruction)

        val order = inOrder(transferRepository, holdRepository, accountRepository)
        order.verify(transferRepository).insert(any(), any(), any(), any())
        order.verify(holdRepository).lockRest(holdId)
        order.verify(accountRepository).lockAvailable(any())
    }

    @Test
    fun `an unknown reference is refused before anything is written`() {
        whenever(holdRepository.findUserHoldByReference(reference)).thenReturn(null)

        val error = assertThrows<HoldNotFoundException> { service.transfer(instruction) }

        assertTrue(error.message!!.contains(reference.toString()))
        verifyNoInteractions(transferRepository, accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `an execution seen before returns the first transfer and locks and books nothing`() {
        val firstTransferUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        aCashHold()
        whenever(transferRepository.insert(any(), eq(TransferKind.FILL), eq(execution), eq(holdId))).thenReturn(null)
        whenever(transferRepository.findUuidByKey(execution)).thenReturn(firstTransferUuid)

        val receipt = service.transfer(instruction)

        assertTrue(receipt.replayed)
        assertEquals(firstTransferUuid, receipt.transferUuid)
        verify(holdRepository, never()).lockRest(any())
        verify(holdRepository, never()).consumeHold(any(), any())
        verifyNoInteractions(accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `a fill for another instrument than the share hold is refused before anything is written`() {
        aShareHold(instrument = "OTHER")

        assertThrows<IllegalArgumentException> { service.transfer(instruction) }

        verifyNoInteractions(transferRepository, accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `a fill in another currency than the cash hold is refused before anything is written`() {
        aCashHold(currency = "USD")

        assertThrows<IllegalArgumentException> { service.transfer(instruction) }

        verifyNoInteractions(transferRepository, accountRepository, ledgerEntryRepository)
    }
}
