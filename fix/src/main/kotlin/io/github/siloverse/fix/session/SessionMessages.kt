package io.github.siloverse.fix.session

import io.github.siloverse.fix.message.FixField
import io.github.siloverse.fix.message.FixMessage
import io.github.siloverse.fix.message.MsgType
import io.github.siloverse.fix.message.Tag
import java.time.Instant

/**
 * Factories for the session-layer messages, one per spec table (FIX Session Layer, 9.1–9.7).
 * Pure functions: everything that varies is a parameter, nothing is remembered.
 */
object SessionMessages {

    /** Logon (35=A). EncryptMethod 0 = none; HeartBtInt in seconds; 141=Y resets both sides to 1. */
    fun logon(
        sender: String, target: String, seq: Long, time: Instant,
        heartBtInt: Int, resetSeqNum: Boolean = false,
    ): FixMessage = FixMessage.create(
        MsgType.LOGON, sender, target, seq, time,
        listOfNotNull(
            FixField(Tag.ENCRYPT_METHOD, "0"),
            FixField(Tag.HEART_BT_INT, heartBtInt.toString()),
            if (resetSeqNum) FixField(Tag.RESET_SEQ_NUM_FLAG, "Y") else null,
        ),
    )

    /** Heartbeat (35=0). Carries 112 only when it answers a TestRequest. */
    fun heartbeat(
        sender: String, target: String, seq: Long, time: Instant,
        testReqId: String? = null,
    ): FixMessage = FixMessage.create(
        MsgType.HEARTBEAT, sender, target, seq, time,
        listOfNotNull(testReqId?.let { FixField(Tag.TEST_REQ_ID, it) }),
    )

    /** TestRequest (35=1). The peer must answer with a Heartbeat carrying the same 112. */
    fun testRequest(
        sender: String, target: String, seq: Long, time: Instant,
        testReqId: String,
    ): FixMessage = FixMessage.create(
        MsgType.TEST_REQUEST, sender, target, seq, time,
        listOf(FixField(Tag.TEST_REQ_ID, testReqId)),
    )

    /** ResendRequest (35=2). EndSeqNo 0 means "everything from BeginSeqNo". */
    fun resendRequest(
        sender: String, target: String, seq: Long, time: Instant,
        beginSeqNo: Long, endSeqNo: Long = 0,
    ): FixMessage = FixMessage.create(
        MsgType.RESEND_REQUEST, sender, target, seq, time,
        listOf(FixField(Tag.BEGIN_SEQ_NO, beginSeqNo.toString()), FixField(Tag.END_SEQ_NO, endSeqNo.toString())),
    )

    /**
     * SequenceReset-GapFill (35=4, 123=Y). Sent in place of admin messages during a resend:
     * it carries the seq of the first skipped message and says "expect [newSeqNo] next".
     * Always marked 43=Y because it travels inside a resend.
     */
    fun sequenceResetGapFill(
        sender: String, target: String, seq: Long, time: Instant,
        newSeqNo: Long,
    ): FixMessage = FixMessage.create(
        MsgType.SEQUENCE_RESET, sender, target, seq, time,
        listOf(FixField(Tag.GAP_FILL_FLAG, "Y"), FixField(Tag.NEW_SEQ_NO, newSeqNo.toString())),
    ).with(FixField(Tag.POSS_DUP_FLAG, "Y"))

    /**
     * SequenceReset-Reset (35=4, 123=N). The emergency form: "forget the gap, expect
     * [newSeqNo] next". Not part of a resend, so no 43=Y. Lost messages stay lost.
     */
    fun sequenceResetReset(
        sender: String, target: String, seq: Long, time: Instant,
        newSeqNo: Long,
    ): FixMessage = FixMessage.create(
        MsgType.SEQUENCE_RESET, sender, target, seq, time,
        listOf(FixField(Tag.GAP_FILL_FLAG, "N"), FixField(Tag.NEW_SEQ_NO, newSeqNo.toString())),
    )

    /** Reject (35=3). For a well-formed message this side will not process; 45 names it. */
    fun reject(
        sender: String, target: String, seq: Long, time: Instant,
        refSeqNum: Long, reason: String, refTagId: Int? = null, refMsgType: String? = null,
    ): FixMessage = FixMessage.create(
        MsgType.REJECT, sender, target, seq, time,
        listOfNotNull(
            FixField(Tag.REF_SEQ_NUM, refSeqNum.toString()),
            refTagId?.let { FixField(Tag.REF_TAG_ID, it.toString()) },
            refMsgType?.let { FixField(Tag.REF_MSG_TYPE, it) },
            FixField(Tag.TEXT, reason),
        ),
    )

    /** Logout (35=5). Sent to start a logout, and sent back to acknowledge one. */
    fun logout(
        sender: String, target: String, seq: Long, time: Instant,
        text: String? = null,
    ): FixMessage = FixMessage.create(
        MsgType.LOGOUT, sender, target, seq, time,
        listOfNotNull(text?.let { FixField(Tag.TEXT, it) }),
    )

    /**
     * Marks a stored message for retransmission: 43=Y, 122 = the SendingTime of the FIRST
     * transmission, 52 = now. Everything else, including MsgSeqNum, stays as it was first
     * sent. Applying it to an already-marked message keeps the first time.
     */
    fun asPossDup(original: FixMessage, resendTime: Instant): FixMessage {
        val originalSendingTime = original[Tag.ORIG_SENDING_TIME] ?: original.require(Tag.SENDING_TIME)
        val fields = original.fields.flatMap { field ->
            when (field.tag) {
                Tag.SENDING_TIME -> listOf(
                    FixField(Tag.POSS_DUP_FLAG, "Y"),
                    FixField(Tag.SENDING_TIME, FixMessage.formatUtcTimestamp(resendTime)),
                    FixField(Tag.ORIG_SENDING_TIME, originalSendingTime),
                )
                Tag.POSS_DUP_FLAG, Tag.ORIG_SENDING_TIME -> emptyList()
                else -> listOf(field)
            }
        }
        return FixMessage(fields)
    }
}
