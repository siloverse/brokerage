package io.github.siloverse.brokerage.ledger.application.withdrawal.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.api.messages.events.TransferBooked
import io.github.siloverse.messaging.core.api.TransactionAwareAsynchronousBus
import io.github.siloverse.brokerage.ledger.application.withdrawal.model.WithdrawalInstruction
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
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
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class WithdrawalServiceTest : BaseUnitTest() {

    @Mock
    lateinit var transferRepository: TransferRepository

    @Mock
    lateinit var accountRepository: AccountRepository

    @Mock
    lateinit var ledgerEntryRepository: LedgerEntryRepository

    @Mock
    lateinit var bus: TransactionAwareAsynchronousBus

    lateinit var service: WithdrawalService

    @BeforeEach
    fun createTheServiceWithARealInvoker() {
        service = WithdrawalService(transferRepository, accountRepository, TransferInvoker(ledgerEntryRepository), bus)
    }

    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val key = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val withdrawalInstruction = WithdrawalInstruction(key, user, 5_000, Currency.EUR)

    private val transferId = 1L
    private val userAccountUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")

    private fun aNewKey(userAccountId: Long = 10L, settlementAccountId: Long = 20L) {
        whenever(transferRepository.insert(any(), eq(TransferKind.WITHDRAWAL), eq(key), isNull())).thenReturn(transferId)
        whenever(accountRepository.ensureUserAccount(user, AssetType.CASH, Currency.EUR))
            .thenReturn(AccountIdentifier(userAccountId, userAccountUuid))
        whenever(accountRepository.ensureSettlementAccount(AssetType.CASH, Currency.EUR))
            .thenReturn(AccountIdentifier(settlementAccountId, UUID.randomUUID()))
    }

    @Test
    fun `a withdrawal writes two entries that sum to zero, takes from the user and adds to settlement`() {
        aNewKey()
        whenever(accountRepository.takeFromAvailable(10L, 5_000)).thenReturn(1)

        service.transfer(withdrawalInstruction)

        verify(ledgerEntryRepository).insert(transferId, 10L, -5_000)
        verify(ledgerEntryRepository).insert(transferId, 20L, 5_000)
        verify(ledgerEntryRepository).findUnbalancedAssets(transferId)
        verifyNoMoreInteractions(ledgerEntryRepository)
        verify(accountRepository).takeFromAvailable(10L, 5_000)
        verify(accountRepository).addToBooked(20L, 5_000)
    }

    @Test
    fun `a withdrawal returns the uuid of the transfer it wrote`() {
        aNewKey()
        whenever(accountRepository.takeFromAvailable(10L, 5_000)).thenReturn(1)

        val receipt = service.transfer(withdrawalInstruction)

        val writtenUuid = argumentCaptor<UUID>()
        verify(transferRepository).insert(writtenUuid.capture(), eq(TransferKind.WITHDRAWAL), eq(key), isNull())
        assertFalse(receipt.replayed)
        assertEquals(writtenUuid.firstValue, receipt.transferUuid)
    }

    @Test
    fun `a booked withdrawal is announced once, with the uuid of its transfer`() {
        aNewKey()
        whenever(accountRepository.takeFromAvailable(10L, 5_000)).thenReturn(1)

        val receipt = service.transfer(withdrawalInstruction)

        verify(bus).publish(TransferBooked(receipt.transferUuid, "WITHDRAWAL"))
    }

    @Test
    fun `too little available is refused and the settlement account is not touched`() {
        aNewKey()
        whenever(accountRepository.takeFromAvailable(10L, 5_000)).thenReturn(0)

        val error = assertThrows<InsufficientFundsException> { service.transfer(withdrawalInstruction) }

        assertTrue(error.message!!.contains(user.toString()))
        assertTrue(error.message!!.contains(userAccountUuid.toString()))
        verify(accountRepository, never()).addToBooked(any(), any())
        verifyNoInteractions(bus)
    }

    @Test
    fun `the user account is never changed without the balance check`() {
        aNewKey()
        whenever(accountRepository.takeFromAvailable(10L, 5_000)).thenReturn(1)

        service.transfer(withdrawalInstruction)

        verify(accountRepository, never()).addToBooked(eq(10L), any())
    }

    @Test
    fun `accounts are updated in ascending id order, user first when its id is lower`() {
        aNewKey(userAccountId = 10L, settlementAccountId = 20L)
        whenever(accountRepository.takeFromAvailable(10L, 5_000)).thenReturn(1)

        service.transfer(withdrawalInstruction)

        val order = inOrder(accountRepository)
        order.verify(accountRepository).takeFromAvailable(10L, 5_000)
        order.verify(accountRepository).addToBooked(20L, 5_000)
    }

    @Test
    fun `accounts are updated in ascending id order, settlement first when its id is lower`() {
        aNewKey(userAccountId = 10L, settlementAccountId = 5L)
        whenever(accountRepository.takeFromAvailable(10L, 5_000)).thenReturn(1)

        service.transfer(withdrawalInstruction)

        val order = inOrder(accountRepository)
        order.verify(accountRepository).addToBooked(5L, 5_000)
        order.verify(accountRepository).takeFromAvailable(10L, 5_000)
    }

    @Test
    fun `a key seen before returns the first transfer and writes nothing`() {
        val firstTransferUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        whenever(transferRepository.insert(any(), eq(TransferKind.WITHDRAWAL), eq(key), isNull())).thenReturn(null)
        whenever(transferRepository.findUuidByKey(key)).thenReturn(firstTransferUuid)

        val receipt = service.transfer(withdrawalInstruction)

        assertTrue(receipt.replayed)
        assertEquals(firstTransferUuid, receipt.transferUuid)
        verifyNoInteractions(accountRepository, ledgerEntryRepository, bus)
    }
}
