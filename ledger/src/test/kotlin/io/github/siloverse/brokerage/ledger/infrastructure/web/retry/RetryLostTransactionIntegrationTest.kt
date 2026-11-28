package io.github.siloverse.brokerage.ledger.infrastructure.web.retry

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import io.github.siloverse.brokerage.ledger.application.deposit.services.DepositFundsService
import io.github.siloverse.brokerage.ledger.application.transfer.model.TransferReceipt
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.PessimisticLockingFailureException
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import java.util.UUID

class RetryLostTransactionIntegrationTest : BaseIntegrationTest() {

    @MockitoBean
    lateinit var depositFundsService: DepositFundsService

    private val booked = TransferReceipt(UUID.randomUUID(), replayed = false)

    private fun deposit(): MockHttpServletResponse =
        mvc.perform(
            post("/api/ledger/deposits")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userUuid":"${UUID.randomUUID()}","currency":"EUR","amount":1000}""")
        ).andReturn().response

    @Test
    fun `a transaction that loses twice is run a third time and the caller sees only the success`() {
        whenever(depositFundsService.transfer(any()))
            .thenThrow(CannotAcquireLockException("could not serialize access due to concurrent update"))
            .thenThrow(PessimisticLockingFailureException("deadlock detected"))
            .thenReturn(booked)

        val response = deposit()

        assertEquals(201, response.status)
        assertTrue(response.contentAsString.contains(booked.transferUuid.toString()))
        verify(depositFundsService, times(3)).transfer(any())
    }

    @Test
    fun `after the first attempt and three retries the caller is told the ledger is busy`() {
        whenever(depositFundsService.transfer(any()))
            .thenThrow(CannotAcquireLockException("could not serialize access due to concurrent update"))

        val response = deposit()

        assertEquals(503, response.status)
        assertEquals("1", response.getHeader("Retry-After"))
        verify(depositFundsService, times(4)).transfer(any())
    }

    @Test
    fun `a request the ledger refuses is not run again`() {
        whenever(depositFundsService.transfer(any())).thenThrow(IllegalArgumentException("amount must be positive"))

        val response = deposit()

        assertEquals(400, response.status)
        verify(depositFundsService, times(1)).transfer(any())
    }
}
