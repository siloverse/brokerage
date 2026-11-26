package io.github.siloverse.brokerage.ledger.application.deposit.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.api.messages.events.TransferBooked
import io.github.siloverse.messaging.core.api.TransactionAwareAsynchronousBus
import io.github.siloverse.brokerage.ledger.application.deposit.model.DepositInstruction
import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.never
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class DepositFundsServiceTest : BaseUnitTest() {

    @Mock
    lateinit var transferRepository: TransferRepository

    @Mock
    lateinit var accountRepository: AccountRepository

    @Mock
    lateinit var ledgerEntryRepository: LedgerEntryRepository

    @Mock
    lateinit var bus: TransactionAwareAsynchronousBus

    lateinit var service: DepositFundsService

    @BeforeEach
    fun createTheServiceWithARealInvoker() {
        service = DepositFundsService(transferRepository, accountRepository, TransferInvoker(ledgerEntryRepository), bus)
    }

    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val key = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val depositInstruction = DepositInstruction(key, user, Currency.EUR, 10_000)

    private val transferId = 1L
    private val userAccountId = 10L
    private val settlementAccountId = 20L

    private fun aNewKey() {
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), isNull())).thenReturn(transferId)
        whenever(accountRepository.ensureUserAccount(user, AssetType.CASH, Currency.EUR)).thenReturn(AccountIdentifier(userAccountId, UUID.randomUUID()))
        whenever(accountRepository.ensureSettlementAccount(AssetType.CASH, Currency.EUR)).thenReturn(AccountIdentifier(settlementAccountId, UUID.randomUUID()))
    }

    @Test
    fun `a deposit writes two entries that sum to zero and moves both balances`() {
        aNewKey()

        service.transfer(depositInstruction)

        verify(ledgerEntryRepository).insert(transferId, userAccountId, 10_000)
        verify(ledgerEntryRepository).insert(transferId, settlementAccountId, -10_000)
        verify(ledgerEntryRepository).findUnbalancedAssets(transferId)
        verifyNoMoreInteractions(ledgerEntryRepository)
        verify(accountRepository).addToBooked(userAccountId, 10_000)
        verify(accountRepository).removeFromBooked(settlementAccountId, 10_000)
    }

    @Test
    fun `a deposit returns the uuid of the transfer it wrote`() {
        aNewKey()

        val receipt = service.transfer(depositInstruction)

        val writtenUuid = argumentCaptor<UUID>()
        verify(transferRepository).insert(writtenUuid.capture(), eq(TransferKind.DEPOSIT), eq(key), isNull())
        assertFalse(receipt.replayed)
        assertEquals(writtenUuid.firstValue, receipt.transferUuid)
    }

    @Test
    fun `a booked deposit is announced once, with the uuid of its transfer`() {
        aNewKey()

        val receipt = service.transfer(depositInstruction)

        verify(bus).publish(TransferBooked(receipt.transferUuid, "DEPOSIT"))
    }

    @Test
    fun `the transfer is written before any entry or balance`() {
        aNewKey()

        service.transfer(depositInstruction)

        val order = inOrder(transferRepository, ledgerEntryRepository, accountRepository)
        order.verify(transferRepository).insert(any(), any(), any(), anyOrNull())
        order.verify(ledgerEntryRepository).insert(transferId, userAccountId, 10_000)
        order.verify(accountRepository).addToBooked(userAccountId, 10_000)
    }

    @Test
    fun `a key seen before returns the first transfer and writes nothing`() {
        val firstTransferUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), isNull())).thenReturn(null)
        whenever(transferRepository.findUuidByKey(key)).thenReturn(firstTransferUuid)

        val receipt = service.transfer(depositInstruction)

        assertTrue(receipt.replayed)
        assertEquals(firstTransferUuid, receipt.transferUuid)
        verifyNoInteractions(accountRepository, ledgerEntryRepository, bus)
    }
}
