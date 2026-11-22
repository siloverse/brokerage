package io.github.siloverse.brokerage.ledger.application.hold.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.application.hold.exception.HoldNotFoundException
import io.github.siloverse.brokerage.ledger.application.hold.model.HoldCommand
import io.github.siloverse.brokerage.ledger.domain.account.exception.InsufficientFundsException
import io.github.siloverse.brokerage.ledger.domain.account.model.AccountIdentifier
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.AssetType
import io.github.siloverse.brokerage.ledger.domain.account.model.enums.Currency
import io.github.siloverse.brokerage.ledger.domain.account.repositories.AccountRepository
import io.github.siloverse.brokerage.ledger.domain.hold.model.ReleasedHold
import io.github.siloverse.brokerage.ledger.domain.hold.repositories.HoldRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class HoldServiceTest : BaseUnitTest() {

    @Mock
    lateinit var accountRepository: AccountRepository

    @Mock
    lateinit var holdRepository: HoldRepository

    @InjectMocks
    lateinit var service: HoldService

    private val user = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val reference = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val command = HoldCommand(reference, user, Currency.EUR, 50_500)

    private val accountId = 10L
    private val accountUuid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
    private val holdId = 7L

    private fun anAccount() {
        whenever(accountRepository.ensureUserAccount(user, AssetType.CASH, Currency.EUR))
            .thenReturn(AccountIdentifier(accountId, accountUuid))
    }

    private fun aNewReference() {
        anAccount()
        whenever(holdRepository.insert(any(), eq(accountId), eq(reference), eq(50_500L))).thenReturn(holdId)
    }

    @Test
    fun `a hold writes the hold row and reserves the amount on the user's account`() {
        aNewReference()
        whenever(accountRepository.reserve(accountId, 50_500)).thenReturn(1)

        service.hold(command)

        verify(holdRepository).insert(any(), eq(accountId), eq(reference), eq(50_500L))
        verify(accountRepository).reserve(accountId, 50_500)
    }

    @Test
    fun `a hold moves no money`() {
        aNewReference()
        whenever(accountRepository.reserve(accountId, 50_500)).thenReturn(1)

        service.hold(command)

        verify(accountRepository, never()).addToBooked(any(), any())
        verify(accountRepository, never()).takeFromAvailable(any(), any())
    }

    @Test
    fun `a hold returns the uuid of the hold it wrote`() {
        aNewReference()
        whenever(accountRepository.reserve(accountId, 50_500)).thenReturn(1)

        val receipt = service.hold(command)

        val writtenUuid = argumentCaptor<UUID>()
        verify(holdRepository).insert(writtenUuid.capture(), eq(accountId), eq(reference), eq(50_500L))
        assertFalse(receipt.replayed)
        assertEquals(writtenUuid.firstValue, receipt.holdUuid)
    }

    @Test
    fun `the hold row is written before the amount is reserved`() {
        aNewReference()
        whenever(accountRepository.reserve(accountId, 50_500)).thenReturn(1)

        service.hold(command)

        val order = inOrder(holdRepository, accountRepository)
        order.verify(holdRepository).insert(any(), any(), any(), any())
        order.verify(accountRepository).reserve(accountId, 50_500)
    }

    @Test
    fun `too little available is refused`() {
        aNewReference()
        whenever(accountRepository.reserve(accountId, 50_500)).thenReturn(0)

        val error = assertThrows<InsufficientFundsException> { service.hold(command) }

        assertTrue(error.message!!.contains(user.toString()))
        assertTrue(error.message!!.contains(accountUuid.toString()))
    }

    @Test
    fun `a reference seen before returns the first hold and reserves nothing`() {
        val firstHoldUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        anAccount()
        whenever(holdRepository.insert(any(), eq(accountId), eq(reference), eq(50_500L))).thenReturn(null)
        whenever(holdRepository.getUuidByReference(reference)).thenReturn(firstHoldUuid)

        val receipt = service.hold(command)

        assertTrue(receipt.replayed)
        assertEquals(firstHoldUuid, receipt.holdUuid)
        verify(accountRepository, never()).reserve(any(), any())
    }

    @Test
    fun `a release closes the hold and gives the rest back on its account`() {
        val holdUuid = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
        whenever(holdRepository.release(reference)).thenReturn(ReleasedHold(holdId, holdUuid, reference, accountId, 30_300))

        val released = service.releaseHold(reference)

        verify(accountRepository).release(accountId, 30_300)
        assertFalse(released.replayed)
        assertEquals(holdUuid, released.holdUuid)
        assertEquals(reference, released.reference)
    }

    @Test
    fun `a release changes the hold row before the account row`() {
        whenever(holdRepository.release(reference)).thenReturn(ReleasedHold(holdId, UUID.randomUUID(), reference, accountId, 30_300))

        service.releaseHold(reference)

        val order = inOrder(holdRepository, accountRepository)
        order.verify(holdRepository).release(reference)
        order.verify(accountRepository).release(accountId, 30_300)
    }

    @Test
    fun `releasing a hold that is already closed changes no account`() {
        val holdUuid = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
        whenever(holdRepository.release(reference)).thenReturn(null)
        whenever(holdRepository.findUuidByReference(reference)).thenReturn(holdUuid)

        val released = service.releaseHold(reference)

        assertTrue(released.replayed)
        assertEquals(holdUuid, released.holdUuid)
        verifyNoInteractions(accountRepository)
    }

    @Test
    fun `releasing an unknown reference is refused and changes no account`() {
        whenever(holdRepository.release(reference)).thenReturn(null)
        whenever(holdRepository.findUuidByReference(reference)).thenReturn(null)

        val error = assertThrows<HoldNotFoundException> { service.releaseHold(reference) }

        assertTrue(error.message!!.contains(reference.toString()))
        verifyNoInteractions(accountRepository)
    }
}
