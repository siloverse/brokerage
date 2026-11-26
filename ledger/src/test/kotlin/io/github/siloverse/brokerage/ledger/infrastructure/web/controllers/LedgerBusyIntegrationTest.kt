package io.github.siloverse.brokerage.ledger.infrastructure.web.controllers

import io.github.siloverse.brokerage.ledger.BaseIntegrationTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A lock that is not released: the request gives up after the lock timeout and says "busy", not "broken". */
class LedgerBusyIntegrationTest : BaseIntegrationTest() {

    @Autowired
    @Qualifier("ledgerTransactionManager")
    lateinit var transactionManager: PlatformTransactionManager

    private val user = UUID.randomUUID()

    private fun deposit(key: UUID): MockHttpServletResponse =
        mvc.perform(
            post("/api/ledger/deposits")
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"userUuid":"$user","currency":"EUR","amount":1000}""")
        ).andReturn().response

    private fun booked(): Long =
        jdbc.sql("select booked from accounts where user_uuid = :user").param("user", user).query(Long::class.java).single()

    @Test
    fun `a deposit that meets a held lock is answered with 503, books nothing, and works when retried`() {
        assertEquals(201, deposit(UUID.randomUUID()).status)
        val key = UUID.randomUUID()
        val locked = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()

        val locker = pool.submit {
            TransactionTemplate(transactionManager).executeWithoutResult {
                jdbc.sql("update accounts set booked = booked where user_uuid = :user").param("user", user).update()
                locked.countDown()
                letGo.await(20, TimeUnit.SECONDS)
            }
        }
        assertTrue(locked.await(10, TimeUnit.SECONDS))

        val refused = deposit(key)

        assertEquals(503, refused.status)
        assertEquals("1", refused.getHeader("Retry-After"))
        assertTrue(refused.contentAsString.contains("busy"))

        letGo.countDown()
        locker.get(10, TimeUnit.SECONDS)
        pool.shutdown()
        assertEquals(1000, booked(), "the refused deposit left nothing behind")

        assertEquals(201, deposit(key).status, "the same key is free to be used again")
        assertEquals(2000, booked())
    }
}
