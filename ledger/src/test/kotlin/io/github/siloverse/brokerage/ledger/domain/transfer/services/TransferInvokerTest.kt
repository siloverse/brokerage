package io.github.siloverse.brokerage.ledger.domain.transfer.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.domain.account.command.Command
import io.github.siloverse.brokerage.ledger.domain.account.model.UnbalancedAsset
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.transfer.exception.UnbalancedTransferException
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class TransferInvokerTest : BaseUnitTest() {

    @Mock
    lateinit var ledgerEntryRepository: LedgerEntryRepository

    private val executed = ArrayList<Long>()

    private fun aCommand(accountId: Long, entryAmount: Long) = object : Command {
        override val accountId = accountId
        override val entryAmount = entryAmount
        override fun execute() {
            executed += accountId
        }
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
        val invoker = TransferInvoker(ledgerEntryRepository)

        invoker.invoke(transferId = 1, commands = listOf(aCommand(30, 1), aCommand(10, 1), aCommand(50, -3), aCommand(20, 1)))

        assertEquals(listOf(10L, 20L, 30L, 50L), executed)
    }

    @Test
    fun `two commands on the same account both leave an entry and both run`() {
        val invoker = TransferInvoker(ledgerEntryRepository)

        invoker.invoke(transferId = 1, commands = listOf(aCommand(10, -200), aCommand(20, 220), aCommand(10, -20)))

        verify(ledgerEntryRepository).insert(1, 10, -200)
        verify(ledgerEntryRepository).insert(1, 10, -20)
        assertEquals(listOf(10L, 10L, 20L), executed)
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
        val invoker = TransferInvoker(ledgerEntryRepository)

        val refused = assertThrows(UnbalancedTransferException::class.java) {
            invoker.invoke(transferId = 1, commands = listOf(aCommand(30, 500), aCommand(10, -400)))
        }

        assertEquals(1, refused.transferId)
        assertEquals(listOf(tooMuch), refused.unbalancedAssets)
        assertTrue(executed.isEmpty())
    }
}
