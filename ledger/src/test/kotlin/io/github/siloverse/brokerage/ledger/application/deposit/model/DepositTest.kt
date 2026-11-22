package io.github.siloverse.brokerage.ledger.application.deposit.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class DepositTest {

    private val key = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `a deposit with a positive amount is valid`() {
        val depositCommand = DepositCommand(key, user, Currency.EUR, 10_000)

        assertEquals(10_000, depositCommand.amount)
        assertEquals(Currency.EUR, depositCommand.currency)
    }

    @Test
    fun `an amount of zero is refused`() {
        val error = assertThrows<IllegalArgumentException> { DepositCommand(key, user, Currency.EUR, 0) }

        assertEquals("amount must be positive", error.message)
    }

    @Test
    fun `a negative amount is refused`() {
        assertThrows<IllegalArgumentException> { DepositCommand(key, user, Currency.EUR, -1) }
    }
}
