package io.github.siloverse.fix.message

/** FIX tags used by the wire and session layers. Application tags live with their users. */
object Tag {
    // standard header and trailer
    const val BEGIN_STRING = 8
    const val BODY_LENGTH = 9
    const val CHECK_SUM = 10
    const val MSG_SEQ_NUM = 34
    const val MSG_TYPE = 35
    const val POSS_DUP_FLAG = 43
    const val SENDER_COMP_ID = 49
    const val SENDING_TIME = 52
    const val TARGET_COMP_ID = 56
    const val ORIG_SENDING_TIME = 122

    // session messages
    const val BEGIN_SEQ_NO = 7
    const val END_SEQ_NO = 16
    const val NEW_SEQ_NO = 36
    const val REF_SEQ_NUM = 45
    const val TEXT = 58
    const val ENCRYPT_METHOD = 98
    const val HEART_BT_INT = 108
    const val TEST_REQ_ID = 112
    const val GAP_FILL_FLAG = 123
    const val RESET_SEQ_NUM_FLAG = 141
    const val REF_TAG_ID = 371
    const val REF_MSG_TYPE = 372
    const val SESSION_REJECT_REASON = 373
}
