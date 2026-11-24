package io.github.siloverse.brokerage.ledger.domain.account.command

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class BookedCommandsTest : BaseUnitTest() {

    @Mock
    lateinit var receiver: AccountRepository

    private val accountUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
    private val userUuid = UUID.fromString("11111111-1111-1111-1111-111111111111")

    @Test
    fun `adding to booked is a positive entry and one call to the receiver`() {
        val command = AddToBookedCommand(receiver, accountId = 10, amount = 5_000)

        assertEquals(10, command.accountId)
        assertEquals(5_000, command.entryAmount)

        command.execute()

        verify(receiver).addToBooked(10, 5_000)
        verifyNoMoreInteractions(receiver)
    }

    @Test
    fun `removing from booked is a negative entry and one call to the receiver with the positive amount`() {
        val command = RemoveFromBookedCommand(receiver, accountId = 20, amount = 5_000)

        assertEquals(20, command.accountId)
        assertEquals(-5_000, command.entryAmount)

        command.execute()

        verify(receiver).removeFromBooked(20, 5_000)
        verifyNoMoreInteractions(receiver)
    }

    @Test
    fun `a command does nothing until it is executed`() {
        AddToBookedCommand(receiver, accountId = 10, amount = 5_000)
        RemoveFromBookedCommand(receiver, accountId = 20, amount = 5_000)

        verifyNoInteractions(receiver)
    }

    @Test
    fun `taking from available is a negative entry and one checked call to the receiver`() {
        whenever(receiver.takeFromAvailable(10, 5_000)).thenReturn(1)
        val command = TakeFromAvailableCommand(receiver, accountId = 10, amount = 5_000, accountUuid = accountUuid, userUuid = userUuid)

        assertEquals(10, command.accountId)
        assertEquals(-5_000, command.entryAmount)

        command.execute()

        verify(receiver).takeFromAvailable(10, 5_000)
        verifyNoMoreInteractions(receiver)
    }

    @Test
    fun `taking from available is refused when the receiver changed no row`() {
        whenever(receiver.takeFromAvailable(10, 5_000)).thenReturn(0)
        val command = TakeFromAvailableCommand(receiver, accountId = 10, amount = 5_000, accountUuid = accountUuid, userUuid = userUuid)

        val error = assertThrows<InsufficientFundsException> { command.execute() }

        assertTrue(error.message!!.contains(accountUuid.toString()))
        assertTrue(error.message!!.contains(userUuid.toString()))
    }

    @Test
    fun `settling a reservation is a negative entry and one call to the receiver`() {
        val command = SettleReservedCommand(receiver, accountId = 10, amount = 5_000)

        assertEquals(10, command.accountId)
        assertEquals(-5_000, command.entryAmount)

        command.execute()

        verify(receiver).settleReserved(10, 5_000)
        verifyNoMoreInteractions(receiver)
    }
}
