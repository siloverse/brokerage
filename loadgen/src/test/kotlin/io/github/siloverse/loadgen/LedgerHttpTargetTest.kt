package io.github.siloverse.loadgen

import io.github.siloverse.brokerage.orders.application.venue.NewOrder
import io.github.siloverse.brokerage.orders.domain.ClientOrderId
import io.github.siloverse.brokerage.orders.domain.Instrument
import io.github.siloverse.brokerage.orders.domain.Price
import io.github.siloverse.brokerage.orders.domain.Quantity
import io.github.siloverse.brokerage.orders.domain.Side
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.atLeast
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.nio.ByteBuffer

class LedgerHttpTargetTest {

    private val http: HttpClient = mock()

    private fun aTarget(users: Int = 100) = LedgerHttpTarget("http://ledger.test", users = users, http = http)

    private fun anOrder(id: String, price: Long = 110_00, quantity: Long = 10) =
        NewOrder(ClientOrderId(id), Instrument("ACME"), Side.BUY, Price(price), Quantity(quantity))

    private fun theLedgerAnswers(status: Int) {
        val response: HttpResponse<Void> = mock { on { statusCode() } doReturn status }
        whenever(http.sendAsync(any(), any<HttpResponse.BodyHandler<Void>>())).thenReturn(CompletableFuture.completedFuture(response))
    }

    private fun theSentRequest(): HttpRequest {
        val sent = argumentCaptor<HttpRequest>()
        verify(http).sendAsync(sent.capture(), any<HttpResponse.BodyHandler<Void>>())
        return sent.firstValue
    }

    private fun bodyOf(request: HttpRequest): String {
        val subscriber: Flow.Subscriber<ByteBuffer> = mock {
            on { onSubscribe(any()) } doAnswer { it.getArgument<Flow.Subscription>(0).request(Long.MAX_VALUE) }
        }
        request.bodyPublisher().get().subscribe(subscriber)
        val chunks = argumentCaptor<ByteBuffer>()
        verify(subscriber, atLeast(0)).onNext(chunks.capture())
        return chunks.allValues.joinToString("") { Charsets.UTF_8.decode(it).toString() }
    }

    @Test
    fun `an order is sent as a hold on cash for price times quantity`() = runBlocking {
        theLedgerAnswers(201)

        val outcome = aTarget().submit(anOrder("r-1", price = 110_00, quantity = 10))

        assertEquals(Outcome.OK, outcome)
        val request = theSentRequest()
        assertEquals("POST", request.method())
        assertEquals("http://ledger.test/api/ledger/holds", request.uri().toString())
        val body = bodyOf(request)
        assertTrue(body.contains(""""assetType":"CASH","assetCode":"EUR","amount":110000"""), body)
    }

    @Test
    fun `a cancel releases the hold of the same order`() = runBlocking {
        theLedgerAnswers(201)
        val target = aTarget()
        target.submit(anOrder("r-7"))
        val reference = Regex(""""reference":"([^"]+)"""").find(bodyOf(theSentRequest()))!!.groupValues[1]

        target.cancel(ClientOrderId("r-7"))

        val sent = argumentCaptor<HttpRequest>()
        verify(http, times(2)).sendAsync(sent.capture(), any<HttpResponse.BodyHandler<Void>>())
        assertEquals("http://ledger.test/api/ledger/holds/$reference/release", sent.secondValue.uri().toString())
    }

    @Test
    fun `two orders get two references, the same order always the same one`() = runBlocking {
        theLedgerAnswers(201)
        val target = aTarget()

        target.submit(anOrder("r-1"))
        target.submit(anOrder("r-2"))
        target.submit(anOrder("r-1"))

        val sent = argumentCaptor<HttpRequest>()
        verify(http, times(3)).sendAsync(sent.capture(), any<HttpResponse.BodyHandler<Void>>())
        val references = sent.allValues.map { Regex(""""reference":"([^"]+)"""").find(bodyOf(it))!!.groupValues[1] }
        assertNotEquals(references[0], references[1])
        assertEquals(references[0], references[2])
    }

    @Test
    fun `the holds are spread over the users of the run`() = runBlocking {
        theLedgerAnswers(201)
        val target = aTarget(users = 10)

        (1..200).forEach { target.submit(anOrder("r-$it")) }

        val sent = argumentCaptor<HttpRequest>()
        verify(http, times(200)).sendAsync(sent.capture(), any<HttpResponse.BodyHandler<Void>>())
        val users = sent.allValues.map { Regex(""""userUuid":"([^"]+)"""").find(bodyOf(it))!!.groupValues[1] }.toSet()
        assertEquals(10, users.size)
    }

    @Test
    fun `the answer decides the outcome`() {
        assertEquals(Outcome.OK, LedgerHttpTarget.outcomeOf(201))
        assertEquals(Outcome.OK, LedgerHttpTarget.outcomeOf(200))
        assertEquals(Outcome.REJECTED, LedgerHttpTarget.outcomeOf(409))
        assertEquals(Outcome.REJECTED, LedgerHttpTarget.outcomeOf(404))
        assertEquals(Outcome.ERROR, LedgerHttpTarget.outcomeOf(500))
        assertEquals(Outcome.ERROR, LedgerHttpTarget.outcomeOf(503))
    }

    @Test
    fun `no answer in time is a timeout, not an error`() = runBlocking {
        whenever(http.sendAsync(any(), any<HttpResponse.BodyHandler<Void>>()))
            .thenReturn(CompletableFuture.failedFuture(HttpTimeoutException("request timed out")))

        assertEquals(Outcome.TIMEOUT, aTarget().submit(anOrder("r-1")))
    }

    @Test
    fun `funding stops the run when the ledger does not take the deposit`() {
        val refused: HttpResponse<String> = mock { on { statusCode() } doReturn 503 }
        whenever(http.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(refused)

        val error = assertThrows<IllegalStateException> { aTarget(users = 3).fundUsers() }

        assertTrue(error.message!!.contains("http 503"))
    }

    @Test
    fun `funding sends one deposit for each user`() {
        val accepted: HttpResponse<String> = mock { on { statusCode() } doReturn 201 }
        whenever(http.send(any(), any<HttpResponse.BodyHandler<String>>())).thenReturn(accepted)

        aTarget(users = 3).fundUsers()

        val sent = argumentCaptor<HttpRequest>()
        verify(http, times(3)).send(sent.capture(), any<HttpResponse.BodyHandler<String>>())
        assertTrue(sent.allValues.all { it.uri().toString() == "http://ledger.test/api/ledger/deposits" })
        assertEquals(3, sent.allValues.map { it.headers().firstValue("Idempotency-Key").get() }.toSet().size)
    }
}
