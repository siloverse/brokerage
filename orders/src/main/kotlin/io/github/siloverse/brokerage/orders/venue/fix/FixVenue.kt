package io.github.siloverse.brokerage.orders.venue.fix

import io.github.siloverse.brokerage.orders.CancelOrder
import io.github.siloverse.brokerage.orders.ClientOrderId
import io.github.siloverse.brokerage.orders.ExecutionReport
import io.github.siloverse.brokerage.orders.Instrument
import io.github.siloverse.brokerage.orders.NewOrder
import io.github.siloverse.brokerage.orders.TopOfBook
import io.github.siloverse.brokerage.orders.Venue
import io.github.siloverse.brokerage.orders.VenueOrderId
import io.github.siloverse.brokerage.orders.VenueOrderView
import io.github.siloverse.brokerage.orders.VenueUnavailableException
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.order.ExecType
import io.github.siloverse.fix.order.OrdStatus
import io.github.siloverse.fix.order.OrderMsgType
import io.github.siloverse.fix.order.OrderTag
import io.github.siloverse.fix.session.FixInitiator
import io.github.siloverse.fix.session.SessionConfig
import io.github.siloverse.fix.session.SessionEvent
import io.github.siloverse.fix.session.SessionListener
import io.github.siloverse.fix.session.SessionRole
import io.github.siloverse.fix.store.MessageStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withTimeout
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The [Venue] port over a FIX session to the external venue. The session layer does the
 * reliable delivery; this class does the mapping and the request-reply waits:
 *
 * - [submit] sends NewOrderSingle and waits for the first ExecutionReport carrying that
 *   ClOrdID to learn the OrderID. A retry after a timeout sends the same ClOrdID again;
 *   the venue answers with ExecType=I and the same OrderID (guarantee 1).
 * - [cancel] sends OrderCancelRequest; the answer is a report on [executions].
 * - [lookup] sends OrderStatusRequest and waits for the ExecType=I answer.
 * - [executions] is a shared flow fed by the session listener. It replays a bounded
 *   history to late subscribers, so a consumer that subscribes after a submit still sees
 *   the reports for it. [SequenceNumber] is the FIX session sequence number.
 *
 * Only ACTIVE sessions send. While the session is down, calls wait up to
 * [FixVenueConfig.connectTimeout] and then throw [VenueUnavailableException]: the "we do
 * not know" outcome, never a guess.
 */
class FixVenue(
    private val config: FixVenueConfig,
    store: MessageStore,
    private val clock: () -> Instant = Instant::now,
) : Venue, AutoCloseable {

    private val reports = MutableSharedFlow<ExecutionReport>(replay = 4096, extraBufferCapacity = 4096)
    private val awaitingOrderId = ConcurrentHashMap<ClientOrderId, CompletableDeferred<VenueOrderId>>()
    private val awaitingStatus = ConcurrentHashMap<ClientOrderId, CompletableDeferred<VenueOrderView?>>()
    private val cancelIds = AtomicLong()
    val events = MutableSharedFlow<SessionEvent>(replay = 64, extraBufferCapacity = 256)

    private val initiator = FixInitiator(
        SessionConfig(config.senderCompId, config.targetCompId, SessionRole.INITIATOR, config.heartBtInt),
        store,
        object : SessionListener {
            override fun onMessage(message: FixMessage) = onInbound(message)
            override fun onEvent(event: SessionEvent) { events.tryEmit(event) }
        },
        host = config.host, port = config.port, backoff = config.reconnectBackoff,
    )

    fun start() = initiator.start()

    override fun close() = initiator.stop()

    val isConnected: Boolean get() = initiator.isActive

    // ---- the port ----------------------------------------------------------------------

    override suspend fun submit(order: NewOrder): VenueOrderId {
        awaitConnected()
        val answer = awaitingOrderId.computeIfAbsent(order.clientOrderId) { CompletableDeferred() }
        try {
            send(OrderMsgType.NEW_ORDER_SINGLE, FixMapping.newOrderBody(order, clock()))
            return withTimeout(config.replyTimeout.toMillis()) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            throw VenueUnavailableException("no answer to submit ${order.clientOrderId.value} within ${config.replyTimeout}", e)
        } finally {
            awaitingOrderId.remove(order.clientOrderId, answer)
        }
    }

    override suspend fun cancel(request: CancelOrder) {
        awaitConnected()
        send(OrderMsgType.ORDER_CANCEL_REQUEST, FixMapping.cancelBody(request, "cxl-${cancelIds.incrementAndGet()}", clock()))
    }

    override suspend fun lookup(id: ClientOrderId): VenueOrderView? {
        awaitConnected()
        val answer = awaitingStatus.computeIfAbsent(id) { CompletableDeferred() }
        try {
            send(OrderMsgType.ORDER_STATUS_REQUEST, FixMapping.statusRequestBody(id))
            return withTimeout(config.replyTimeout.toMillis()) { answer.await() }
        } catch (e: TimeoutCancellationException) {
            throw VenueUnavailableException("no answer to status request ${id.value} within ${config.replyTimeout}", e)
        } finally {
            awaitingStatus.remove(id, answer)
        }
    }

    override fun executions(): Flow<ExecutionReport> = reports.asSharedFlow()

    override fun quotes(instrument: Instrument): Flow<TopOfBook> =
        TODO("market data arrives with chapter 4 (MarketDataRequest 35=V)")

    // ---- inbound -------------------------------------------------------------------------

    private fun onInbound(message: FixMessage) {
        when (message.msgType) {
            OrderMsgType.EXECUTION_REPORT -> {
                val clientOrderId = ClientOrderId(message[OrderTag.CL_ORD_ID] ?: return)
                val execType = ExecType.fromCode(message[OrderTag.EXEC_TYPE] ?: return)
                if (execType == ExecType.ORDER_STATUS) {
                    val unknown = message[OrderTag.ORD_STATUS] == OrdStatus.REJECTED.code && message[OrderTag.TEXT] == UNKNOWN_ORDER_TEXT
                    awaitingStatus[clientOrderId]?.complete(if (unknown) null else FixMapping.orderView(message))
                    if (!unknown) awaitingOrderId[clientOrderId]?.complete(VenueOrderId(message.require(OrderTag.ORDER_ID)))
                    return
                }
                val report = FixMapping.executionReport(message) ?: return
                awaitingOrderId[clientOrderId]?.complete(report.venueOrderId)   // New, Rejected or an immediate Trade all carry OrderID
                reports.tryEmit(report)
            }
            OrderMsgType.ORDER_CANCEL_REJECT -> reports.tryEmit(FixMapping.cancelReject(message))
            else -> Unit
        }
    }

    // ---- helpers -------------------------------------------------------------------------

    private suspend fun awaitConnected() {
        val deadline = System.nanoTime() + config.connectTimeout.toNanos()
        while (!initiator.isActive) {
            if (System.nanoTime() > deadline) throw VenueUnavailableException("FIX session to ${config.host}:${config.port} is not logged on")
            delay(20)
        }
    }

    private fun send(msgType: String, body: List<io.github.siloverse.fix.message.FixField>) {
        try {
            initiator.send(msgType, body)
        } catch (e: IllegalStateException) {
            throw VenueUnavailableException("FIX session dropped while sending $msgType", e)
        }
    }

    private companion object {
        /** The venue's text on an ExecType=I answer for an order it never had. */
        const val UNKNOWN_ORDER_TEXT = "unknown order"
    }
}
