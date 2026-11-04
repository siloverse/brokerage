package io.github.siloverse.fix.order

/** Tags of the order-flow messages (FIX 4.4 volume 4). Numbers only; meaning lives with the user. */
object OrderTag {
    const val AVG_PX = 6
    const val CL_ORD_ID = 11
    const val CUM_QTY = 14
    const val EXEC_ID = 17
    const val LAST_PX = 31
    const val LAST_QTY = 32
    const val ORDER_ID = 37
    const val ORDER_QTY = 38
    const val ORD_STATUS = 39
    const val ORD_TYPE = 40
    const val ORIG_CL_ORD_ID = 41
    const val PRICE = 44
    const val SIDE = 54
    const val SYMBOL = 55
    const val TEXT = 58
    const val TIME_IN_FORCE = 59
    const val TRANSACT_TIME = 60
    const val CXL_REJ_REASON = 102
    const val ORD_REJ_REASON = 103
    const val EXEC_TYPE = 150
    const val LEAVES_QTY = 151
    const val CXL_REJ_RESPONSE_TO = 434
}
