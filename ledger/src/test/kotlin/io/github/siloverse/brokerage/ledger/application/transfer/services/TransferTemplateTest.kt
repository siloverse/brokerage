package io.github.siloverse.brokerage.ledger.application.transfer.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.api.messages.events.TransferBooked
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import io.github.siloverse.messaging.core.api.TransactionAwareAsynchronousBus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class TransferTemplateTest : BaseUnitTest() {

    @Mock
    lateinit var transferRepository: TransferRepository

    @Mock
    lateinit var bus: TransactionAwareAsynchronousBus

    private val key = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    private fun aTransfer(refuseInPrepare: Boolean = false, holdId: Long? = null) =
        RecordingTransfer(transferRepository, bus, key, refuseInPrepare, holdId)

    @Test
    fun `a new key runs prepare, then the gate, then book, and returns the transfer it wrote`() {
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), isNull())).thenReturn(7L)
        val transfer = aTransfer()

        val receipt = transfer.transfer("instruction")

        assertEquals(listOf("prepare(instruction)", "book(instruction, prepared, 7)"), transfer.steps)
        val writtenUuid = argumentCaptor<UUID>()
        verify(transferRepository).insert(writtenUuid.capture(), eq(TransferKind.DEPOSIT), eq(key), isNull())
        assertFalse(receipt.replayed)
        assertEquals(writtenUuid.firstValue, receipt.transferUuid)
    }

    @Test
    fun `a booked transfer is announced after the gate, with its uuid and its kind`() {
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), isNull())).thenReturn(7L)

        val receipt = aTransfer().transfer("instruction")

        val order = inOrder(transferRepository, bus)
        order.verify(transferRepository).insert(any(), eq(TransferKind.DEPOSIT), eq(key), isNull())
        order.verify(bus).publish(TransferBooked(receipt.transferUuid, "DEPOSIT"))
    }

    @Test
    fun `a key seen before returns the first transfer, never books and announces nothing`() {
        val firstTransferUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), isNull())).thenReturn(null)
        whenever(transferRepository.findUuidByKey(key)).thenReturn(firstTransferUuid)
        val transfer = aTransfer()

        val receipt = transfer.transfer("instruction")

        assertEquals(listOf("prepare(instruction)"), transfer.steps)
        assertTrue(receipt.replayed)
        assertEquals(firstTransferUuid, receipt.transferUuid)
        verifyNoInteractions(bus)
    }

    @Test
    fun `a refusal in prepare happens before anything is written or announced`() {
        val transfer = aTransfer(refuseInPrepare = true)

        assertThrows<IllegalArgumentException> { transfer.transfer("instruction") }

        assertEquals(listOf("prepare(instruction)"), transfer.steps)
        verifyNoInteractions(transferRepository, bus)
    }

    @Test
    fun `the hold id hook is empty by default and a subclass can supply one`() {
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), eq(42L))).thenReturn(7L)

        aTransfer(holdId = 42).transfer("instruction")

        verify(transferRepository).insert(any(), eq(TransferKind.DEPOSIT), eq(key), eq(42L))
    }
}
