package io.github.siloverse.fix.message

/** FIX tags used by the wire and session layers. Application tags live with their users. */
object Tag {
    const val BEGIN_STRING = 8
    const val BODY_LENGTH = 9
    const val CHECK_SUM = 10
    const val MSG_SEQ_NUM = 34
    const val MSG_TYPE = 35
    const val SENDER_COMP_ID = 49
    const val TARGET_COMP_ID = 56
    const val SENDING_TIME = 52
}
