package io.github.siloverse.brokerage.ledger.domain.transfer.strategy

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.model.UserHold
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.Fill
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
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

class FillStrategiesTest : BaseUnitTest() {

    @Mock
    lateinit var holdRepository: HoldRepository

    @Mock
    lateinit var accountRepository: AccountRepository

    @Mock
    lateinit var ledgerEntryRepository: LedgerEntryRepository

    private lateinit var buy: BuyFillStrategy
    private lateinit var sell: SellFillStrategy

    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val holdUuid = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
    private val fill = Fill("ACME", quantity = 4, currency = Currency.EUR, amount = 20_000, fee = 200)

    private val holdId = 7L
    private val transferId = 1L
    private val userCash = 10L
    private val settlementCash = 20L
    private val userPosition = 30L
    private val venuePosition = 40L
    private val feeIncome = 50L
    private val cashReceivable = 60L
    private val shareReceivable = 70L

    private val cashHold = UserHold(holdId, holdUuid, userCash, user, "EUR", AssetType.CASH)
    private val shareHold = UserHold(holdId, holdUuid, userPosition, user, "ACME", AssetType.POSITION)

    private fun account(id: Long) = AccountIdentifier(id, UUID.randomUUID())

    @BeforeEach
    fun createTheStrategies() {
        val transferInvoker = TransferInvoker(ledgerEntryRepository)
        buy = BuyFillStrategy(holdRepository, accountRepository, transferInvoker)
        sell = SellFillStrategy(holdRepository, accountRepository, transferInvoker)
    }

    private fun aBuyWith(holdRest: Long, available: Long) {
        whenever(holdRepository.lockRest(holdId)).thenReturn(holdRest)
        whenever(accountRepository.ensureAccount(AccountType.USER, AssetType.POSITION, "ACME", user)).thenReturn(account(userPosition))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.POSITION, "ACME", null)).thenReturn(account(venuePosition))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.CASH, "EUR", null)).thenReturn(account(settlementCash))
        whenever(accountRepository.ensureAccount(AccountType.FEE_INCOME, AssetType.CASH, "EUR", null)).thenReturn(account(feeIncome))
        whenever(accountRepository.lockAvailable(any())).thenReturn(mapOf(userCash to available))
    }

    private fun aSellWith(holdRest: Long, available: Long) {
        whenever(holdRepository.lockRest(holdId)).thenReturn(holdRest)
        whenever(accountRepository.ensureAccount(AccountType.USER, AssetType.CASH, "EUR", user)).thenReturn(account(userCash))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.POSITION, "ACME", null)).thenReturn(account(venuePosition))
        whenever(accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.CASH, "EUR", null)).thenReturn(account(settlementCash))
        whenever(accountRepository.ensureAccount(AccountType.FEE_INCOME, AssetType.CASH, "EUR", null)).thenReturn(account(feeIncome))
        whenever(accountRepository.lockAvailable(any())).thenReturn(mapOf(userPosition to available))
    }

    private fun aCashReceivable() {
        whenever(accountRepository.ensureAccount(AccountType.CUSTOMER_RECEIVABLE, AssetType.CASH, "EUR", user)).thenReturn(account(cashReceivable))
    }

    private fun aShareReceivable() {
        whenever(accountRepository.ensureAccount(AccountType.CUSTOMER_RECEIVABLE, AssetType.POSITION, "ACME", user)).thenReturn(account(shareReceivable))
    }

    private fun noReceivableIsOpened() {
        verify(accountRepository, never()).ensureAccount(eq(AccountType.CUSTOMER_RECEIVABLE), any(), any(), any())
    }

    // ---- which strategy, and the check that a fill fits its hold

    @Test
    fun `the buy strategy handles holds on cash, the sell strategy holds on positions`() {
        assertEquals(AssetType.CASH, buy.assetType)
        assertEquals(AssetType.POSITION, sell.assetType)
    }

    @Test
    fun `a buy is valid when the hold is in the fill's currency`() {
        assertDoesNotThrow { buy.validate(fill, cashHold) }
        assertThrows<IllegalArgumentException> { buy.validate(fill, cashHold.copy(assetCode = "USD")) }
    }

    @Test
    fun `a sell is valid when the hold is on the fill's instrument`() {
        assertDoesNotThrow { sell.validate(fill, shareHold) }
        assertThrows<IllegalArgumentException> { sell.validate(fill, shareHold.copy(assetCode = "OTHER")) }
    }

    @Test
    fun `validating touches no repository`() {
        buy.validate(fill, cashHold)
        sell.validate(fill, shareHold)

        verifyNoInteractions(holdRepository, accountRepository, ledgerEntryRepository)
    }

    // ---- a buy

    @Test
    fun `a buy the hold covers leaves through the reservation in one entry`() {
        aBuyWith(holdRest = 50_500, available = 49_500)
        whenever(holdRepository.consumeHold(holdId, 20_200)).thenReturn(1)

        buy.book(fill, cashHold, transferId)

        verify(holdRepository).consumeHold(holdId, 20_200)
        verify(ledgerEntryRepository).insert(transferId, userCash, -20_200)
        verify(ledgerEntryRepository).insert(transferId, settlementCash, 20_000)
        verify(ledgerEntryRepository).insert(transferId, feeIncome, 200)
        verify(ledgerEntryRepository).insert(transferId, venuePosition, -4)
        verify(ledgerEntryRepository).insert(transferId, userPosition, 4)
        verify(ledgerEntryRepository).findUnbalancedAssets(transferId)
        verifyNoMoreInteractions(ledgerEntryRepository)
        verify(accountRepository).settleReserved(userCash, 20_200)
        verify(accountRepository).addToBooked(settlementCash, 20_000)
        verify(accountRepository).addToBooked(feeIncome, 200)
        verify(accountRepository).removeFromBooked(venuePosition, 4)
        verify(accountRepository).addToBooked(userPosition, 4)
        verify(accountRepository, never()).removeFromBooked(eq(userCash), any())
        noReceivableIsOpened()
    }

    @Test
    fun `what the hold cannot cover is taken from the free balance, as a second entry`() {
        aBuyWith(holdRest = 5_000, available = 49_500)
        whenever(holdRepository.consumeHold(holdId, 5_000)).thenReturn(1)

        buy.book(fill, cashHold, transferId)

        verify(holdRepository).consumeHold(holdId, 5_000)
        verify(ledgerEntryRepository).insert(transferId, userCash, -5_000)
        verify(ledgerEntryRepository).insert(transferId, userCash, -15_200)
        verify(accountRepository).settleReserved(userCash, 5_000)
        verify(accountRepository).removeFromBooked(userCash, 15_200)
        noReceivableIsOpened()
    }

    @Test
    fun `a released hold is not consumed, the free balance pays what it has, the rest goes to the receivable`() {
        aBuyWith(holdRest = 0, available = 10_000)
        aCashReceivable()

        buy.book(fill, cashHold, transferId)

        verify(holdRepository, never()).consumeHold(any(), any())
        verify(accountRepository, never()).settleReserved(any(), any())
        verify(ledgerEntryRepository).insert(transferId, userCash, -10_000)
        verify(ledgerEntryRepository).insert(transferId, cashReceivable, -10_200)
        verify(accountRepository).removeFromBooked(userCash, 10_000)
        verify(accountRepository).removeFromBooked(cashReceivable, 10_200)
        verify(accountRepository).addToBooked(settlementCash, 20_000)
        verify(accountRepository).addToBooked(feeIncome, 200)
        verify(accountRepository).addToBooked(userPosition, 4)
    }

    @Test
    fun `a buy can need all three sources, and its cash entries still sum to zero`() {
        aBuyWith(holdRest = 5_000, available = 10_000)
        whenever(holdRepository.consumeHold(holdId, 5_000)).thenReturn(1)
        aCashReceivable()

        buy.book(fill, cashHold, transferId)

        val accounts = argumentCaptor<Long>()
        val amounts = argumentCaptor<Long>()
        verify(ledgerEntryRepository, org.mockito.kotlin.times(7)).insert(eq(transferId), accounts.capture(), amounts.capture())
        val entries = accounts.allValues.zip(amounts.allValues)
        val cashAccounts = setOf(userCash, cashReceivable, settlementCash, feeIncome)
        assertEquals(0, entries.filter { it.first in cashAccounts }.sumOf { it.second })
        assertEquals(0, entries.filter { it.first !in cashAccounts }.sumOf { it.second })
        assertEquals(listOf(-5_000L, -10_000L), entries.filter { it.first == userCash }.map { it.second })
        assertEquals(listOf(-5_200L), entries.filter { it.first == cashReceivable }.map { it.second })
    }

    @Test
    fun `with nothing in the hold and nothing free the whole buy is a receivable and the user's cash is not touched`() {
        aBuyWith(holdRest = 0, available = 0)
        aCashReceivable()

        buy.book(fill, cashHold, transferId)

        verify(accountRepository).removeFromBooked(cashReceivable, 20_200)
        verify(accountRepository, never()).removeFromBooked(eq(userCash), any())
        verify(accountRepository, never()).settleReserved(any(), any())
        verify(accountRepository).addToBooked(userPosition, 4)
    }

    @Test
    fun `a buy without a fee writes no fee entry`() {
        aBuyWith(holdRest = 50_500, available = 49_500)
        whenever(holdRepository.consumeHold(holdId, 20_000)).thenReturn(1)

        buy.book(fill.copy(fee = 0), cashHold, transferId)

        verify(ledgerEntryRepository).insert(transferId, userCash, -20_000)
        verify(ledgerEntryRepository).insert(transferId, settlementCash, 20_000)
        verify(ledgerEntryRepository).insert(transferId, venuePosition, -4)
        verify(ledgerEntryRepository).insert(transferId, userPosition, 4)
        verify(ledgerEntryRepository).findUnbalancedAssets(transferId)
        verifyNoMoreInteractions(ledgerEntryRepository)
        verify(accountRepository, never()).addToBooked(eq(feeIncome), any())
    }

    @Test
    fun `a buy locks the hold, then the accounts, and only then changes anything, in ascending account id`() {
        aBuyWith(holdRest = 50_500, available = 49_500)
        whenever(holdRepository.consumeHold(holdId, 20_200)).thenReturn(1)

        buy.book(fill, cashHold, transferId)

        val locked = argumentCaptor<List<Long>>()
        val order = inOrder(holdRepository, accountRepository)
        order.verify(holdRepository).lockRest(holdId)
        order.verify(accountRepository).lockAvailable(locked.capture())
        order.verify(holdRepository).consumeHold(holdId, 20_200)
        order.verify(accountRepository).settleReserved(userCash, 20_200)
        order.verify(accountRepository).addToBooked(settlementCash, 20_000)
        order.verify(accountRepository).addToBooked(userPosition, 4)
        order.verify(accountRepository).removeFromBooked(venuePosition, 4)
        order.verify(accountRepository).addToBooked(feeIncome, 200)
        assertEquals(setOf(userCash, settlementCash, userPosition, venuePosition, feeIncome), locked.firstValue.toSet())
    }

    // ---- a sell

    @Test
    fun `a sell the hold covers takes the shares out of the reservation and pays the price minus the fee`() {
        aSellWith(holdRest = 6, available = 4)
        whenever(holdRepository.consumeHold(holdId, 4)).thenReturn(1)

        sell.book(fill, shareHold, transferId)

        verify(holdRepository).consumeHold(holdId, 4)
        verify(ledgerEntryRepository).insert(transferId, userPosition, -4)
        verify(ledgerEntryRepository).insert(transferId, venuePosition, 4)
        verify(ledgerEntryRepository).insert(transferId, settlementCash, -20_000)
        verify(ledgerEntryRepository).insert(transferId, userCash, 20_000)
        verify(ledgerEntryRepository).insert(transferId, userCash, -200)
        verify(ledgerEntryRepository).insert(transferId, feeIncome, 200)
        verify(ledgerEntryRepository).findUnbalancedAssets(transferId)
        verifyNoMoreInteractions(ledgerEntryRepository)
        verify(accountRepository).settleReserved(userPosition, 4)
        verify(accountRepository).addToBooked(venuePosition, 4)
        verify(accountRepository).removeFromBooked(settlementCash, 20_000)
        verify(accountRepository).addToBooked(userCash, 20_000)
        verify(accountRepository).removeFromBooked(userCash, 200)
        verify(accountRepository).addToBooked(feeIncome, 200)
        noReceivableIsOpened()
    }

    @Test
    fun `a sell counts its funding in shares, hold, then free shares, then a share receivable`() {
        aSellWith(holdRest = 1, available = 2)
        whenever(holdRepository.consumeHold(holdId, 1)).thenReturn(1)
        aShareReceivable()

        sell.book(fill, shareHold, transferId)

        verify(holdRepository).consumeHold(holdId, 1)
        verify(accountRepository).settleReserved(userPosition, 1)
        verify(accountRepository).removeFromBooked(userPosition, 2)
        verify(accountRepository).removeFromBooked(shareReceivable, 1)
        verify(ledgerEntryRepository).insert(transferId, userPosition, -1)
        verify(ledgerEntryRepository).insert(transferId, userPosition, -2)
        verify(ledgerEntryRepository).insert(transferId, shareReceivable, -1)
        verify(ledgerEntryRepository).insert(transferId, venuePosition, 4)
        verify(accountRepository).addToBooked(userCash, 20_000)
    }

    @Test
    fun `a sell after its hold was released takes the shares from the free position`() {
        aSellWith(holdRest = 0, available = 10)

        sell.book(fill, shareHold, transferId)

        verify(holdRepository, never()).consumeHold(any(), any())
        verify(accountRepository, never()).settleReserved(any(), any())
        verify(accountRepository).removeFromBooked(userPosition, 4)
        noReceivableIsOpened()
    }

    @Test
    fun `a sell locks the hold, then the accounts, and only then changes anything, in ascending account id`() {
        aSellWith(holdRest = 6, available = 4)
        whenever(holdRepository.consumeHold(holdId, 4)).thenReturn(1)

        sell.book(fill, shareHold, transferId)

        val locked = argumentCaptor<List<Long>>()
        val order = inOrder(holdRepository, accountRepository)
        order.verify(holdRepository).lockRest(holdId)
        order.verify(accountRepository).lockAvailable(locked.capture())
        order.verify(holdRepository).consumeHold(holdId, 4)
        order.verify(accountRepository).addToBooked(userCash, 20_000)
        order.verify(accountRepository).removeFromBooked(userCash, 200)
        order.verify(accountRepository).removeFromBooked(settlementCash, 20_000)
        order.verify(accountRepository).settleReserved(userPosition, 4)
        order.verify(accountRepository).addToBooked(venuePosition, 4)
        order.verify(accountRepository).addToBooked(feeIncome, 200)
        assertEquals(setOf(userCash, settlementCash, userPosition, venuePosition, feeIncome), locked.firstValue.toSet())
    }
}
