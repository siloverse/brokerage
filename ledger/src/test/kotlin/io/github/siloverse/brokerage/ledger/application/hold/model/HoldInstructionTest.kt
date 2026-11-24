package io.github.siloverse.brokerage.ledger.application.hold.model

import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class HoldInstructionTest {

    private val reference = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `a hold with a positive amount is valid`() {
        val instruction = HoldInstruction(reference, user, AssetType.CASH, "EUR", 50_500)

        assertEquals(50_500, instruction.amount)
        assertEquals(AssetType.CASH, instruction.assetType)
        assertEquals("EUR", instruction.assetCode)
    }

    @Test
    fun `an amount of zero is refused`() {
        val error = assertThrows<IllegalArgumentException> { HoldInstruction(reference, user, AssetType.CASH, "EUR", 0) }

        assertEquals("amount must be positive", error.message)
    }

    @Test
    fun `a negative amount is refused`() {
        assertThrows<IllegalArgumentException> { HoldInstruction(reference, user, AssetType.CASH, "EUR", -1) }
    }

    @Test
    fun `a hold on shares names the instrument and counts whole shares`() {
        val instruction = HoldInstruction(reference, user, AssetType.POSITION, "ACME", 6)

        assertEquals(AssetType.POSITION, instruction.assetType)
        assertEquals("ACME", instruction.assetCode)
        assertEquals(6, instruction.amount)
    }

    @Test
    fun `a cash hold in an unknown currency is refused`() {
        assertThrows<IllegalArgumentException> { HoldInstruction(reference, user, AssetType.CASH, "EURO", 50_500) }
    }

    @Test
    fun `a blank asset code is refused`() {
        assertThrows<IllegalArgumentException> { HoldInstruction(reference, user, AssetType.POSITION, " ", 6) }
    }
}
