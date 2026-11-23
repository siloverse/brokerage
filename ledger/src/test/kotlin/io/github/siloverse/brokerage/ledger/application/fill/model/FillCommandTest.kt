package io.github.siloverse.brokerage.ledger.application.fill.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class FillCommandTest {

    private val execution = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
    private val reference = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    private fun command(assetCode: String = "ACME", quantity: Long = 4, amount: Long = 20_000, fee: Long = 200) =
        FillCommand(execution, reference, assetCode, quantity, amount, fee)

    @Test
    fun `a fill with shares, a price and a fee is valid`() {
        val fill = command()

        assertEquals("ACME", fill.assetCode)
        assertEquals(4, fill.quantity)
        assertEquals(20_000, fill.amount)
        assertEquals(200, fill.fee)
    }

    @Test
    fun `a fee of zero is allowed`() {
        assertEquals(0, command(fee = 0).fee)
    }

    @Test
    fun `a blank instrument is refused`() {
        val error = assertThrows<IllegalArgumentException> { command(assetCode = " ") }

        assertEquals("instrument must not be blank", error.message)
    }

    @Test
    fun `a quantity of zero or less is refused`() {
        assertEquals("quantity must be positive", assertThrows<IllegalArgumentException> { command(quantity = 0) }.message)
        assertThrows<IllegalArgumentException> { command(quantity = -1) }
    }

    @Test
    fun `an amount of zero or less is refused`() {
        assertEquals("amount must be positive", assertThrows<IllegalArgumentException> { command(amount = 0) }.message)
        assertThrows<IllegalArgumentException> { command(amount = -1) }
    }

    @Test
    fun `a negative fee is refused`() {
        assertEquals("fee must not be negative", assertThrows<IllegalArgumentException> { command(fee = -1) }.message)
    }
}
