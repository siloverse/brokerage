package io.github.siloverse.fix.order

/** Message type codes (tag 35) of the order-flow messages we speak. */
object OrderMsgType {
    const val NEW_ORDER_SINGLE = "D"
    const val ORDER_CANCEL_REQUEST = "F"
    const val EXECUTION_REPORT = "8"
    const val ORDER_CANCEL_REJECT = "9"
    const val ORDER_STATUS_REQUEST = "H"
}
