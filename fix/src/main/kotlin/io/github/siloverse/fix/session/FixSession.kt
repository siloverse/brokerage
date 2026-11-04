package io.github.siloverse.fix.session

import io.github.siloverse.fix.codec.FixDecoder
import io.github.siloverse.fix.codec.FixEncoder
import io.github.siloverse.fix.codec.FixFramer
import io.github.siloverse.fix.codec.FixGarbledException
import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.message.MissingTagException
import io.github.siloverse.fix.message.MsgType
import io.github.siloverse.fix.message.Tag
import io.github.siloverse.fix.store.MessageStore
import java.time.Duration
import java.time.Instant
import java.util.TreeMap

/**
 * The FIX session state machine, with no threads and no clock of its own. The driver
 * feeds it bytes ([onBytes]), time ([onTick]) and application messages ([send]); every
 * call returns a [SessionOutput] saying what to write, deliver and do. Tests feed the
 * same calls and assert on the output.
 *
 * ## The rules (FIX Session Layer spec, chapter 4), each one a test in FixSessionTest
 * 1. Every outbound message takes the next number from the store and is stored before
 *    it is returned for sending ([MessageStore.append]).
 * 2. Inbound seq == expected: process it, expected becomes seq + 1.
 * 3. Inbound seq > expected: hold it, send one ResendRequest(expected, 0), process held
 *    messages as the gap closes.
 * 4. Inbound seq < expected: with 43=Y ignore it; without, Logout and disconnect.
 * 5. On the peer's ResendRequest: replay stored application messages with 43=Y and 122;
 *    replace admin messages and holes with a SequenceReset-GapFill.
 * 6. Send a Heartbeat after [heartBtInt] of outbound silence; a TestRequest after
 *    [silenceThreshold] (the interval plus 20%) of inbound silence; disconnect when that
 *    TestRequest goes unanswered for another threshold. Both use the NEGOTIATED interval.
 * 7. Logon carries the heartbeat interval; the acceptor's value wins. 141=Y on Logon
 *    resets both stores to 1 and is echoed back.
 *
 * Not thread-safe: the driver serialises all calls.
 */
class FixSession(
    val config: SessionConfig,
    private val store: MessageStore,
) {
    var state: SessionState = SessionState.DISCONNECTED
        private set

    /** The interval in force, after Logon negotiation. */
    var heartBtInt: Duration = config.heartBtInt
        private set

    /** Inbound silence tolerated before a TestRequest, and again before a disconnect: the interval plus 20%. */
    val silenceThreshold: Duration get() = heartBtInt.plus(heartBtInt.dividedBy(5))

    private var framer = FixFramer()
    private var lastSent: Instant = Instant.EPOCH
    private var lastReceived: Instant = Instant.EPOCH
    private var connectedAt: Instant = Instant.EPOCH
    private var testRequestSentAt: Instant? = null
    private var testRequestCounter = 0L
    private var resendOutstanding = false
    private var resetSentOnLogon = false
    private var forceResetOnNextLogon = false
    private var logonConsumedByReset = false
    private val held = TreeMap<Long, FixMessage>()
    private var gapFillTarget: Long? = null

    val nextOutbound: Long get() = store.nextOutbound()
    val nextExpectedInbound: Long get() = store.nextExpectedInbound()

    // ---- driver entry points ------------------------------------------------------------

    /** A transport is open. The initiator sends Logon; the acceptor waits for one. */
    fun connect(now: Instant): SessionOutput {
        check(state == SessionState.DISCONNECTED) { "connect while $state" }
        framer = FixFramer()
        held.clear()
        resendOutstanding = false
        testRequestSentAt = null
        connectedAt = now
        lastReceived = now
        return when (config.role) {
            SessionRole.INITIATOR -> {
                if (config.resetOnLogon) store.reset()
                resetSentOnLogon = config.resetOnLogon
                state = SessionState.LOGON_SENT
                emit(now) { seq -> SessionMessages.logon(sender, target, seq, now, heartBtInt.seconds.toInt(), config.resetOnLogon) }
            }
            SessionRole.ACCEPTOR -> {
                state = SessionState.AWAITING_LOGON
                SessionOutput.NONE
            }
        }
    }

    /**
     * Acceptor only: on the next Logon, reset both stores to 1 and answer with 141=Y even
     * though the peer did not ask. The operator's "we lost our sequence state" button, and
     * a stage D chaos knob. The peer accepts the reset as spec 4.4.3 allows.
     */
    fun forceResetOnNextLogon() {
        check(config.role == SessionRole.ACCEPTOR) { "only an acceptor can impose a reset" }
        forceResetOnNextLogon = true
    }

    /** The transport is gone, for whatever reason. Counters survive; the connection state does not. */
    fun disconnected(): SessionOutput {
        state = SessionState.DISCONNECTED
        held.clear()
        return SessionOutput.NONE
    }

    /** Bytes from the transport, in any chunking. */
    fun onBytes(bytes: ByteArray, now: Instant): SessionOutput {
        check(state != SessionState.DISCONNECTED) { "bytes while disconnected" }
        var output = SessionOutput.NONE
        for (frame in framer.feed(bytes)) {
            if (state == SessionState.DISCONNECTED) break
            val message = try {
                FixDecoder.decode(frame)
            } catch (e: FixGarbledException) {
                output += SessionOutput(events = listOf(SessionEvent.Garbled(e.message ?: "garbled")))
                continue
            }
            lastReceived = now
            output += onMessage(message, now)
        }
        return output
    }

    /** Time passed. Call it often (every few hundred milliseconds); it is cheap and idempotent. */
    fun onTick(now: Instant): SessionOutput = when (state) {
        SessionState.DISCONNECTED -> SessionOutput.NONE
        SessionState.LOGON_SENT, SessionState.AWAITING_LOGON ->
            if (Duration.between(connectedAt, now) >= config.logonTimeout) disconnect("no Logon within ${config.logonTimeout}")
            else SessionOutput.NONE
        SessionState.ACTIVE, SessionState.LOGOUT_SENT -> {
            var output = SessionOutput.NONE
            val sentAt = testRequestSentAt
            if (sentAt != null && Duration.between(sentAt, now) >= silenceThreshold) {
                return disconnect("no response to TestRequest")
            }
            if (sentAt == null && Duration.between(lastReceived, now) >= silenceThreshold) {
                val id = "TEST-${++testRequestCounter}"
                testRequestSentAt = now
                output += emit(now) { seq -> SessionMessages.testRequest(sender, target, seq, now, id) }
            }
            if (Duration.between(lastSent, now) >= heartBtInt) {
                output += emit(now) { seq -> SessionMessages.heartbeat(sender, target, seq, now) }
            }
            output
        }
    }

    /** An application message to send. Only while ACTIVE; the application queues otherwise. */
    fun send(msgType: String, body: List<FixField>, now: Instant): SessionOutput {
        check(state == SessionState.ACTIVE) { "send while $state" }
        require(msgType !in MsgType.ADMIN) { "session messages are the session's business" }
        return emit(now) { seq -> FixMessage.create(msgType, sender, target, seq, now, body) }
    }

    /** Start a clean logout. The transport is dropped when the peer's Logout arrives, or on timeout. */
    fun logout(now: Instant, text: String? = null): SessionOutput {
        check(state == SessionState.ACTIVE) { "logout while $state" }
        state = SessionState.LOGOUT_SENT
        return emit(now) { seq -> SessionMessages.logout(sender, target, seq, now, text) }
    }

    // ---- inbound ---------------------------------------------------------------------------

    private fun onMessage(message: FixMessage, now: Instant): SessionOutput {
        val header = try {
            Header(message.msgType, message.seqNum, message.require(Tag.SENDER_COMP_ID), message.require(Tag.TARGET_COMP_ID))
        } catch (e: MissingTagException) {
            return logoutAndDisconnect(now, "missing header tag ${e.tag}")
        } catch (e: NumberFormatException) {
            return logoutAndDisconnect(now, "MsgSeqNum is not a number")
        }
        if (header.sender != target || header.target != sender) {
            return logoutAndDisconnect(now, "CompID mismatch: ${header.sender}->${header.target}")
        }

        var output = SessionOutput.NONE

        // Rule 7: a Logon is answered before its sequence number is judged, so the reply
        // and any ResendRequest both go out on an established session.
        if (header.type == MsgType.LOGON) {
            when (state) {
                SessionState.LOGON_SENT, SessionState.AWAITING_LOGON -> output += acceptLogon(message, now)
                else -> return logoutAndDisconnect(now, "Logon while $state")
            }
            // A reset this side forced: the peer's Logon carried its OLD number and must not be
            // judged against the fresh counters. Both sides now continue from 1.
            if (logonConsumedByReset) { logonConsumedByReset = false; return output }
        } else if (state == SessionState.LOGON_SENT || state == SessionState.AWAITING_LOGON) {
            return logoutAndDisconnect(now, "first message must be Logon, got ${header.type}")
        }

        // SequenceReset-Reset is the one message judged by NewSeqNo, not by its own number.
        if (header.type == MsgType.SEQUENCE_RESET && message[Tag.GAP_FILL_FLAG] != "Y") {
            return output + applySequenceReset(message, now)
        }

        val expected = store.nextExpectedInbound()
        val seq = header.seq
        return output + when {
            seq == expected -> processInOrder(message, now)
            seq > expected -> holdAndRequest(message, expected, now)
            message[Tag.POSS_DUP_FLAG] == "Y" -> SessionOutput.NONE   // rule 4a: a resend we already have
            else -> logoutAndDisconnect(now, "MsgSeqNum too low, expecting $expected but received $seq")   // rule 4b
        }
    }

    /** Rule 2, then drain whatever the gap was holding back. */
    private fun processInOrder(message: FixMessage, now: Instant): SessionOutput {
        var output = SessionOutput.NONE
        var current: FixMessage? = message
        while (current != null && state != SessionState.DISCONNECTED) {
            gapFillTarget = null
            output += process(current, now)
            val next = gapFillTarget ?: (current.seqNum + 1)
            store.setNextExpectedInbound(next)
            held.headMap(next).clear()
            current = held.remove(next)
        }
        if (held.isEmpty()) resendOutstanding = false
        return output
    }

    /** Rule 3. */
    private fun holdAndRequest(message: FixMessage, expected: Long, now: Instant): SessionOutput {
        held[message.seqNum] = message
        if (resendOutstanding) return SessionOutput.NONE
        resendOutstanding = true
        return emit(now) { seq -> SessionMessages.resendRequest(sender, target, seq, now, expected, 0) } +
            SessionOutput(events = listOf(SessionEvent.GapDetected(expected, 0)))
    }

    /** What an in-order message means. Sequence bookkeeping is done by the caller. */
    private fun process(message: FixMessage, now: Instant): SessionOutput = when (message.msgType) {
        MsgType.LOGON -> SessionOutput.NONE   // already accepted
        MsgType.HEARTBEAT -> {
            if (message[Tag.TEST_REQ_ID] != null) testRequestSentAt = null
            SessionOutput.NONE
        }
        MsgType.TEST_REQUEST -> {
            val id = message[Tag.TEST_REQ_ID]
            emit(now) { seq -> SessionMessages.heartbeat(sender, target, seq, now, id) }
        }
        MsgType.RESEND_REQUEST -> resend(message, now)
        MsgType.SEQUENCE_RESET -> applyGapFill(message, now)
        MsgType.REJECT -> SessionOutput(events = listOf(SessionEvent.PeerRejected(message.getLong(Tag.REF_SEQ_NUM) ?: 0, message[Tag.TEXT])))
        MsgType.LOGOUT -> {
            val text = message[Tag.TEXT]
            val ack = if (state == SessionState.LOGOUT_SENT) SessionOutput.NONE
            else emit(now) { seq -> SessionMessages.logout(sender, target, seq, now) }
            state = SessionState.DISCONNECTED
            ack + SessionOutput(events = listOf(SessionEvent.PeerLoggedOut(text), SessionEvent.Disconnect("peer logged out")))
        }
        else -> SessionOutput(applicationMessages = listOf(message))
    }

    /** Rule 7. */
    private fun acceptLogon(logon: FixMessage, now: Instant): SessionOutput {
        val peerInterval = logon.getInt(Tag.HEART_BT_INT) ?: return logoutAndDisconnect(now, "Logon without HeartBtInt")
        val peerReset = logon[Tag.RESET_SEQ_NUM_FLAG] == "Y"
        var output = SessionOutput.NONE
        when (config.role) {
            SessionRole.ACCEPTOR -> {
                val reset = peerReset || forceResetOnNextLogon
                if (reset) store.reset()
                if (forceResetOnNextLogon && !peerReset) logonConsumedByReset = true
                forceResetOnNextLogon = false
                heartBtInt = config.heartBtInt   // the acceptor's interval wins
                state = SessionState.ACTIVE
                output += emit(now) { seq -> SessionMessages.logon(sender, target, seq, now, heartBtInt.seconds.toInt(), reset) }
            }
            SessionRole.INITIATOR -> {
                if (resetSentOnLogon && !peerReset) return logoutAndDisconnect(now, "peer did not acknowledge sequence reset")
                // The acceptor may impose a reset we did not ask for (4.4.3): accept it, both sides restart at 1.
                if (peerReset && !resetSentOnLogon) store.reset()
                heartBtInt = Duration.ofSeconds(peerInterval.toLong())
                state = SessionState.ACTIVE
            }
        }
        testRequestSentAt = null
        return output + SessionOutput(events = listOf(SessionEvent.LoggedOn))
    }

    /** Rule 5. Resent messages keep their numbers and are NOT appended to the store. */
    private fun resend(request: FixMessage, now: Instant): SessionOutput {
        val begin = request.getLong(Tag.BEGIN_SEQ_NO) ?: return reject(request, "missing BeginSeqNo", now)
        val requestedEnd = request.getLong(Tag.END_SEQ_NO) ?: 0
        val lastSentSeq = store.nextOutbound() - 1
        val end = if (requestedEnd == 0L || requestedEnd > lastSentSeq) lastSentSeq else requestedEnd
        if (begin > end) return SessionOutput.NONE

        val stored = store.outbound(begin, end).associateBy { it.seq }
        val bytes = mutableListOf<ByteArray>()
        var gapStart: Long? = null
        fun flushGap(nextRealSeq: Long) {
            val start = gapStart ?: return
            bytes += FixEncoder.encode(SessionMessages.sequenceResetGapFill(sender, target, start, now, nextRealSeq))
            gapStart = null
        }
        for (seq in begin..end) {
            val original = stored[seq]?.let { FixDecoder.decode(it.bytes) }
            if (original == null || original.msgType in MsgType.ADMIN) {
                if (gapStart == null) gapStart = seq
            } else {
                flushGap(seq)
                bytes += FixEncoder.encode(SessionMessages.asPossDup(original, now))
            }
        }
        flushGap(end + 1)
        lastSent = now
        return SessionOutput(bytesToSend = bytes)
    }

    /** Rule 5, receiving side: a gap fill at the expected number moves expected to NewSeqNo. */
    private fun applyGapFill(message: FixMessage, now: Instant): SessionOutput {
        val newSeqNo = message.getLong(Tag.NEW_SEQ_NO) ?: return reject(message, "missing NewSeqNo", now)
        if (newSeqNo <= message.seqNum) return reject(message, "NewSeqNo $newSeqNo not above ${message.seqNum}", now)
        gapFillTarget = newSeqNo
        return SessionOutput.NONE
    }

    /** The emergency reset (123=N): expected jumps to NewSeqNo whatever this message's own number was. */
    private fun applySequenceReset(message: FixMessage, now: Instant): SessionOutput {
        val newSeqNo = message.getLong(Tag.NEW_SEQ_NO) ?: return reject(message, "missing NewSeqNo", now)
        val expected = store.nextExpectedInbound()
        if (newSeqNo < expected) return reject(message, "NewSeqNo $newSeqNo below expected $expected", now)
        store.setNextExpectedInbound(newSeqNo)
        held.headMap(newSeqNo).clear()
        resendOutstanding = false
        return SessionOutput.NONE
    }

    // ---- outbound helpers -------------------------------------------------------------------

    private val sender get() = config.senderCompId
    private val target get() = config.targetCompId

    /** Rule 1: the store assigns the number and keeps the bytes before anything is returned for sending. */
    private fun emit(now: Instant, build: (seq: Long) -> FixMessage): SessionOutput {
        val stored = store.append { seq -> FixEncoder.encode(build(seq)) }
        lastSent = now
        return SessionOutput(bytesToSend = listOf(stored.bytes))
    }

    private fun reject(offending: FixMessage, reason: String, now: Instant): SessionOutput =
        emit(now) { seq -> SessionMessages.reject(sender, target, seq, now, offending.seqNum, reason, refMsgType = offending.msgType) }

    private fun logoutAndDisconnect(now: Instant, reason: String): SessionOutput {
        val logout = if (state == SessionState.DISCONNECTED) SessionOutput.NONE
        else emit(now) { seq -> SessionMessages.logout(sender, target, seq, now, reason) }
        return logout + disconnect(reason)
    }

    private fun disconnect(reason: String): SessionOutput {
        state = SessionState.DISCONNECTED
        held.clear()
        return SessionOutput(events = listOf(SessionEvent.Disconnect(reason)))
    }

    private data class Header(val type: String, val seq: Long, val sender: String, val target: String)
}
