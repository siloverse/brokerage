package io.github.siloverse.brokerage.ledger.infrastructure.persistence.repositories

import io.github.siloverse.brokerage.ledger.domain.account.model.AccountType
import io.github.siloverse.brokerage.ledger.domain.account.model.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.Currency
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
    ): Long {
        val accountId = insertAccount(
            accountType = AccountType.USER,
            assetType = assetType,
            assetCode = currency.name,
            userUuid = userUuid
        )
        if (accountId != null) {
            return accountId
        }
        return getAccountId(
            accountType = AccountType.USER,
            assetType = assetType,
            assetCode = currency.name,
            userUuid = userUuid
        )
    }

    override fun ensureSettlementAccount(
        assetType: AssetType,
        currency: Currency
    ): Long {
        val accountId = insertAccount(
            accountType = AccountType.VENUE_SETTLEMENT,
            assetType = assetType,
            assetCode = currency.name,
        )
        if (accountId != null) {
            return accountId
        }
        return getAccountId(
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

    private fun insertAccount(
        accountType: AccountType,
        assetType: AssetType,
        assetCode: String,
        userUuid: UUID? = null,
    ): Long? {
        return jdbc.sql(
            """
                insert into accounts (uuid, user_uuid, type, asset_type, asset_code)
                values (:uuid, :user_uuid, :type, :asset_type, :asset_code)
                on conflict on constraint accounts_one_per_asset do nothing
                returning id
            """.trimIndent()
        )
            .param("uuid", UuidV7.next())
            .param("user_uuid", userUuid)
            .param("type", accountType.name)
            .param("asset_type", assetType.name)
            .param("asset_code", assetCode)
            .query(Long::class.java)
            .optional()
            .orElse(null)
    }

    private fun getAccountId(
        accountType: AccountType,
        assetType: AssetType,
        assetCode: String,
        userUuid: UUID? = null,
    ): Long {
        return jdbc.sql(
            """
                select id 
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
            .query(Long::class.java)
            .single()
    }
}
