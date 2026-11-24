package io.github.siloverse.brokerage.ledger.domain.transfer.strategy

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.exception.HoldFillException
import io.github.siloverse.brokerage.ledger.domain.hold.model.UserHold
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.Fill
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.verifyNoInteractions
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

    private fun aHoldOn(assetType: AssetType, assetCode: String) = UserHold(7, holdUuid, 10, user, assetCode, assetType)

    @BeforeEach
    fun createTheStrategies() {
        val transferInvoker = TransferInvoker(ledgerEntryRepository)
        buy = BuyFillStrategy(holdRepository, accountRepository, transferInvoker)
        sell = SellFillStrategy(holdRepository, accountRepository, transferInvoker)
    }

    @Test
    fun `the buy strategy handles holds on cash, the sell strategy holds on positions`() {
        assertEquals(AssetType.CASH, buy.assetType)
        assertEquals(AssetType.POSITION, sell.assetType)
    }

    @Test
    fun `a buy is valid when the hold is in the fill's currency`() {
        assertDoesNotThrow { buy.validate(fill, aHoldOn(AssetType.CASH, "EUR")) }
        assertThrows<IllegalArgumentException> { buy.validate(fill, aHoldOn(AssetType.CASH, "USD")) }
    }

    @Test
    fun `a sell is valid when the hold is on the fill's instrument`() {
        assertDoesNotThrow { sell.validate(fill, aHoldOn(AssetType.POSITION, "ACME")) }
        assertThrows<IllegalArgumentException> { sell.validate(fill, aHoldOn(AssetType.POSITION, "OTHER")) }
    }

    @Test
    fun `validating touches no repository`() {
        buy.validate(fill, aHoldOn(AssetType.CASH, "EUR"))
        sell.validate(fill, aHoldOn(AssetType.POSITION, "ACME"))

        verifyNoInteractions(holdRepository, accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `a buy that the hold cannot cover names the hold and the money it needed`() {
        whenever(holdRepository.consumeHold(7, 20_200)).thenReturn(0)

        val error = assertThrows<HoldFillException> { buy.book(fill, aHoldOn(AssetType.CASH, "EUR"), transferId = 1) }

        assertTrue(error.message!!.contains(holdUuid.toString()))
        assertTrue(error.message!!.contains("20200"))
        verifyNoInteractions(accountRepository, ledgerEntryRepository)
    }

    @Test
    fun `a sell that the hold cannot cover names the hold and the shares it needed`() {
        whenever(holdRepository.consumeHold(7, 4)).thenReturn(0)

        val error = assertThrows<HoldFillException> { sell.book(fill, aHoldOn(AssetType.POSITION, "ACME"), transferId = 1) }

        assertTrue(error.message!!.contains(holdUuid.toString()))
        assertTrue(error.message!!.contains("requestedAmount:[4]"))
        verifyNoInteractions(accountRepository, ledgerEntryRepository)
    }
}
