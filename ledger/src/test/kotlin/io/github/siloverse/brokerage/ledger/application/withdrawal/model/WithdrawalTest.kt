package io.github.siloverse.brokerage.ledger.application.withdrawal.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class WithdrawalTest {

    private val key = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `a withdrawal with a positive amount is valid`() {
        val withdrawal = Withdrawal(key, user, 10_000, Currency.EUR)

        assertEquals(10_000, withdrawal.amount)
        assertEquals(Currency.EUR, withdrawal.currency)
    }

    @Test
    fun `an amount of zero is refused`() {
        val error = assertThrows<IllegalArgumentException> { Withdrawal(key, user, 0, Currency.EUR) }

        assertEquals("amount must be positive", error.message)
    }

    @Test
    fun `a negative amount is refused`() {
        assertThrows<IllegalArgumentException> { Withdrawal(key, user, -1, Currency.EUR) }
    }
}
