package io.github.siloverse.brokerage.ledger.application.transfer.services

import io.github.siloverse.brokerage.ledger.BaseUnitTest
import io.github.siloverse.brokerage.ledger.domain.transfer.model.TransferKind
import io.github.siloverse.brokerage.ledger.domain.transfer.repositories.TransferRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import java.util.UUID

class TransferTemplateTest : BaseUnitTest() {

    @Mock
    lateinit var transferRepository: TransferRepository

    private val key = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val steps = ArrayList<String>()

    /** A ConcreteClass that only records which primitive operations ran, and in which order. */
    private open inner class RecordingTransfer(
        private val refuseInPrepare: Boolean = false,
    ) : TransferTemplate<String, String>(transferRepository) {
        override val kind = TransferKind.DEPOSIT
        override fun idempotencyKey(instruction: String) = key
        override fun prepare(instruction: String): String {
            steps += "prepare($instruction)"
            require(!refuseInPrepare) { "refused before the gate" }
            return "prepared"
        }

        override fun book(instruction: String, prepared: String, transferId: Long) {
            steps += "book($instruction, $prepared, $transferId)"
        }
    }

    @Test
    fun `a new key runs prepare, then the gate, then book, and returns the transfer it wrote`() {
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), isNull())).thenReturn(7L)

        val receipt = RecordingTransfer().transfer("instruction")

        assertEquals(listOf("prepare(instruction)", "book(instruction, prepared, 7)"), steps)
        val writtenUuid = argumentCaptor<UUID>()
        verify(transferRepository).insert(writtenUuid.capture(), eq(TransferKind.DEPOSIT), eq(key), isNull())
        assertFalse(receipt.replayed)
        assertEquals(writtenUuid.firstValue, receipt.transferUuid)
    }

    @Test
    fun `a key seen before returns the first transfer and never books`() {
        val firstTransferUuid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), isNull())).thenReturn(null)
        whenever(transferRepository.findUuidByKey(key)).thenReturn(firstTransferUuid)

        val receipt = RecordingTransfer().transfer("instruction")

        assertEquals(listOf("prepare(instruction)"), steps)
        assertTrue(receipt.replayed)
        assertEquals(firstTransferUuid, receipt.transferUuid)
    }

    @Test
    fun `a refusal in prepare happens before anything is written`() {
        assertThrows<IllegalArgumentException> { RecordingTransfer(refuseInPrepare = true).transfer("instruction") }

        assertEquals(listOf("prepare(instruction)"), steps)
        verifyNoInteractions(transferRepository)
    }

    @Test
    fun `the hold id hook is empty by default and a subclass can supply one`() {
        whenever(transferRepository.insert(any(), eq(TransferKind.DEPOSIT), eq(key), eq(42L))).thenReturn(7L)
        val withHold = object : RecordingTransfer() {
            override fun holdId(prepared: String): Long = 42
        }

        withHold.transfer("instruction")

        verify(transferRepository).insert(any(), eq(TransferKind.DEPOSIT), eq(key), eq(42L))
    }
}
