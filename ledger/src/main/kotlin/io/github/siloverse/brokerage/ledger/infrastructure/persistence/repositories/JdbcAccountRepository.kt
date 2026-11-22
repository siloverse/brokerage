package io.github.siloverse.brokerage.ledger.infrastructure.persistence.repositories

import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.identifiers.core.UuidV7
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class JdbcAccountRepository(@Qualifier("ledgerJdbc") private val jdbc: JdbcClient) : AccountRepository {

    override fun ensureUserAccount(
        userUuid: UUID,
        assetType: AssetType,
        currency: Currency
    ): AccountIdentifier {
        val accountIdentifier = insertAccount(
            accountType = AccountType.USER,
            assetType = assetType,
            assetCode = currency.name,
            userUuid = userUuid
        )
        if (accountIdentifier != null) {
            return accountIdentifier
        }
        return getAccountIdentifier(
            accountType = AccountType.USER,
            assetType = assetType,
            assetCode = currency.name,
            userUuid = userUuid
        )
    }

    override fun ensureSettlementAccount(
        assetType: AssetType,
        currency: Currency
    ): AccountIdentifier {
        val accountIdentifier = insertAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = assetType,
            assetCode = currency.name,
        )
        if (accountIdentifier != null) {
            return accountIdentifier
        }
        return getAccountIdentifier(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = assetType,
            assetCode = currency.name,
        )
    }

    override fun addToBooked(accountId: Long, amount: Long) {
        jdbc.sql(
            """
                update accounts
                set booked = booked + :amount, updated_at = now()
                where id = :id
            """.trimIndent()
        )
            .param("id", accountId)
            .param("amount", amount)
            .update()
    }

    override fun takeFromAvailable(accountId: Long, amount: Long): Int {
        return jdbc.sql(
            """
                update accounts
                set booked = booked - :amount, updated_at = now()
                where id = :id 
                and available >= :amount
            """.trimIndent()
        )
            .param("id", accountId)
            .param("amount", amount)
            .update()
    }

    private fun insertAccount(
        accountType: AccountType,
        assetType: AssetType,
        assetCode: String,
        userUuid: UUID? = null,
    ): AccountIdentifier? {
        return jdbc.sql(
            """
                insert into accounts (uuid, user_uuid, type, asset_type, asset_code)
                values (:uuid, :user_uuid, :type, :asset_type, :asset_code)
                on conflict on constraint accounts_one_per_asset do nothing
                returning id,uuid
            """.trimIndent()
        )
            .param("uuid", UuidV7.next())
            .param("user_uuid", userUuid)
            .param("type", accountType.name)
            .param("asset_type", assetType.name)
            .param("asset_code", assetCode)
            .query(AccountIdentifier::class.java)
            .optional()
            .orElse(null)
    }

    private fun getAccountIdentifier(
        accountType: AccountType,
        assetType: AssetType,
        assetCode: String,
        userUuid: UUID? = null,
    ): AccountIdentifier {
        return jdbc.sql(
            """
                select id, uuid 
                from accounts
                where user_uuid is not distinct from :user_uuid 
                AND type=:type
                AND asset_type=:asset_type
                AND asset_code=:asset_code
            """.trimIndent()
        )
            .param("user_uuid", userUuid)
            .param("type", accountType.name)
            .param("asset_type", assetType.name)
            .param("asset_code", assetCode)
            .query(AccountIdentifier::class.java)
            .single()
    }

    override fun reserve(accountId: Long, amount: Long): Int {
        return jdbc.sql(
            """
                update accounts
                set reserved = reserved + :amount, updated_at = now()
                where id = :id 
                and available >= :amount
            """.trimIndent()
        )
            .param("id", accountId)
            .param("amount", amount)
            .update()
    }

    override fun release(accountId: Long, amount: Long): Int {
        return jdbc.sql(
            """
                update accounts
                set reserved = reserved - :amount, updated_at = now()
                where id = :id
            """.trimIndent()
        )
            .param("id", accountId)
            .param("amount", amount)
            .update()
    }
}
