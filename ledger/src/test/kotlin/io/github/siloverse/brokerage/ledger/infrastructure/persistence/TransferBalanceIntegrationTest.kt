package io.github.siloverse.brokerage.ledger.infrastructure.persistence

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import io.github.siloverse.brokerage.ledger.domain.account.command.AddToBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.command.Command
import io.github.siloverse.brokerage.ledger.domain.account.command.RemoveFromBookedCommand
import io.github.siloverse.brokerage.ledger.domain.account.model.UnbalancedAsset
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.exception.UnbalancedTransferException
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.LedgerEntryRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.brokerage.ledger.domain.transfer.services.TransferInvoker
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** Every transfer sums to zero for each asset it touches, checked against the real database. */
class TransferBalanceIntegrationTest : BaseIntegrationTest() {

    @Autowired
    lateinit var transferRepository: TransferRepository

    @Autowired
    lateinit var ledgerEntryRepository: LedgerEntryRepository

    @Autowired
    lateinit var accountRepository: AccountRepository

    @Autowired
    lateinit var transferInvoker: TransferInvoker

    @Autowired
    @Qualifier("ledgerTransactionManager")
    lateinit var transactionManager: PlatformTransactionManager

    private val instrument = "T" + UUID.randomUUID().toString().take(8)
    private val user = UUID.randomUUID()

    private fun <T : Any> inTransaction(work: (TransactionStatus) -> T): T =
        TransactionTemplate(transactionManager).execute { work(it) }!!

    private fun userCash() = accountRepository.ensureAccount(AccountType.USER, AssetType.CASH, "EUR", user).id
    private fun venueCash() = accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.CASH, "EUR").id
    private fun userShares() = accountRepository.ensureAccount(AccountType.USER, AssetType.POSITION, instrument, user).id
    private fun venueShares() = accountRepository.ensureAccount(AccountType.VENUE_SETTLEMENT, AssetType.POSITION, instrument).id

    private fun add(accountId: Long, amount: Long): Command = AddToBookedCommand(accountRepository, accountId, amount)
    private fun remove(accountId: Long, amount: Long): Command = RemoveFromBookedCommand(accountRepository, accountId, amount)

    /** Writes a transfer with these commands and returns what the database says is off. */
    private fun unbalancedAssetsOf(commands: () -> List<Command>): List<UnbalancedAsset> = inTransaction {
        val transferId = transferRepository.insert(UUID.randomUUID(), TransferKind.DEPOSIT, UUID.randomUUID())!!
        commands().forEach { ledgerEntryRepository.insert(transferId, it.accountId, it.entryAmount) }
        val found = ledgerEntryRepository.findUnbalancedAssets(transferId)
        it.setRollbackOnly()
        found
    }

    private fun count(sql: String, name: String, value: Any): Long =
        jdbc.sql(sql).param(name, value).query(Long::class.java).single()

    @Test
    fun `a transfer whose entries cancel out has no unbalanced asset`() {
        val found = unbalancedAssetsOf { listOf(add(userCash(), 10_000), remove(venueCash(), 10_000)) }

        assertTrue(found.isEmpty())
    }

    @Test
    fun `a transfer over two assets is balanced when each asset cancels out on its own`() {
        val found = unbalancedAssetsOf {
            listOf(remove(userCash(), 20_000), add(venueCash(), 20_000), remove(venueShares(), 4), add(userShares(), 4))
        }

        assertTrue(found.isEmpty())
    }

    @Test
    fun `euros and shares that only cancel out together are both reported`() {
        val found = unbalancedAssetsOf { listOf(add(userCash(), 4), remove(venueShares(), 4)) }

        assertEquals(
            setOf(UnbalancedAsset(AssetType.CASH, "EUR", 4), UnbalancedAsset(AssetType.POSITION, instrument, -4)),
            found.toSet(),
        )
    }

    @Test
    fun `only the asset that is off is reported`() {
        val found = unbalancedAssetsOf {
            listOf(remove(userCash(), 20_000), add(venueCash(), 20_000), remove(venueShares(), 4), add(userShares(), 5))
        }

        assertEquals(listOf(UnbalancedAsset(AssetType.POSITION, instrument, 1)), found)
    }

    @Test
    fun `the invoker refuses an unbalanced transfer and nothing of it stays`() {
        val transferUuid = UUID.randomUUID()

        val refused = assertThrows(UnbalancedTransferException::class.java) {
            inTransaction {
                val transferId = transferRepository.insert(transferUuid, TransferKind.DEPOSIT, UUID.randomUUID())!!
                transferInvoker.invoke(transferId, listOf(add(userCash(), 10_000), remove(venueCash(), 9_000)))
            }
        }

        assertEquals(listOf(UnbalancedAsset(AssetType.CASH, "EUR", 1_000)), refused.unbalancedAssets)
        assertEquals(0, count("select count(*) from transfers where uuid = :uuid", "uuid", transferUuid))
        assertEquals(0, count("select count(*) from ledger_entries where transfer_id = :id", "id", refused.transferId))
        assertEquals(0, count("select count(*) from accounts where user_uuid = :user", "user", user))
    }
}
