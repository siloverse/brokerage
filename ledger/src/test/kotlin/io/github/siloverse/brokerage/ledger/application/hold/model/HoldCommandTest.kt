package io.github.siloverse.brokerage.ledger.application.hold.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class HoldCommandTest {

    private val reference = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `a hold with a positive amount is valid`() {
        val command = HoldCommand(reference, user, Currency.EUR, 50_500)

        assertEquals(50_500, command.amount)
        assertEquals(Currency.EUR, command.currency)
    }

    @Test
    fun `an amount of zero is refused`() {
        val error = assertThrows<IllegalArgumentException> { HoldCommand(reference, user, Currency.EUR, 0) }

        assertEquals("amount must be positive", error.message)
    }

    @Test
    fun `a negative amount is refused`() {
        assertThrows<IllegalArgumentException> { HoldCommand(reference, user, Currency.EUR, -1) }
    }
}
