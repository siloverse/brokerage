package io.github.siloverse.brokerage.ledger.domain.transfer.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.domain.account.command.Command
import io.github.siloverse.brokerage.ledger.domain.account.model.UnbalancedAsset
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.transfer.exception.UnbalancedTransferException
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TransferInvokerTest : BaseUnitTest() {

    @Mock
    lateinit var ledgerEntryRepository: LedgerEntryRepository

    private fun aCommand(accountId: Long, entryAmount: Long): Command = mock {
        on { this.accountId } doReturn accountId
        on { this.entryAmount } doReturn entryAmount
    }

    @Test
    fun `every command leaves one ledger entry with its account and its signed amount`() {
        val invoker = TransferInvoker(ledgerEntryRepository)

        invoker.invoke(transferId = 1, commands = listOf(aCommand(30, 400), aCommand(10, -400)))

        verify(ledgerEntryRepository).insert(1, 30, 400)
        verify(ledgerEntryRepository).insert(1, 10, -400)
    }

    @Test
    fun `commands are executed in ascending account id, whatever order they were given in`() {
        val on30 = aCommand(30, 1)
        val on10 = aCommand(10, 1)
        val on50 = aCommand(50, -3)
        val on20 = aCommand(20, 1)
        val invoker = TransferInvoker(ledgerEntryRepository)

        invoker.invoke(transferId = 1, commands = listOf(on30, on10, on50, on20))

        val order = inOrder(on10, on20, on30, on50)
        order.verify(on10).execute()
        order.verify(on20).execute()
        order.verify(on30).execute()
        order.verify(on50).execute()
    }

    @Test
    fun `two commands on the same account both leave an entry and both run`() {
        val price = aCommand(10, -200)
        val received = aCommand(20, 220)
        val fee = aCommand(10, -20)
        val invoker = TransferInvoker(ledgerEntryRepository)

        invoker.invoke(transferId = 1, commands = listOf(price, received, fee))

        verify(ledgerEntryRepository).insert(1, 10, -200)
        verify(ledgerEntryRepository).insert(1, 10, -20)
        val order = inOrder(price, fee, received)
        order.verify(price).execute()
        order.verify(fee).execute()
        order.verify(received).execute()
    }

    @Test
    fun `the balance of the transfer is asked for after its entries are written`() {
        val invoker = TransferInvoker(ledgerEntryRepository)

        invoker.invoke(transferId = 1, commands = listOf(aCommand(30, 400), aCommand(10, -400)))

        val order = inOrder(ledgerEntryRepository)
        order.verify(ledgerEntryRepository).insert(1, 30, 400)
        order.verify(ledgerEntryRepository).insert(1, 10, -400)
        order.verify(ledgerEntryRepository).findUnbalancedAssets(1)
    }

    @Test
    fun `a transfer that does not sum to zero is refused before any command runs`() {
        val tooMuch = UnbalancedAsset(AssetType.CASH, "EUR", 100)
        whenever(ledgerEntryRepository.findUnbalancedAssets(1)).thenReturn(listOf(tooMuch))
        val added = aCommand(30, 500)
        val removed = aCommand(10, -400)
        val invoker = TransferInvoker(ledgerEntryRepository)

        val refused = assertThrows(UnbalancedTransferException::class.java) {
            invoker.invoke(transferId = 1, commands = listOf(added, removed))
        }

        assertEquals(1, refused.transferId)
        assertEquals(listOf(tooMuch), refused.unbalancedAssets)
        verify(added, never()).execute()
        verify(removed, never()).execute()
    }
}
