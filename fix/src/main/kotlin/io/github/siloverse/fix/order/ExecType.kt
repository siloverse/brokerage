package io.github.siloverse.fix.order

/** ExecType (150): what this ExecutionReport announces. The port's report hierarchy is named after it. */
enum class ExecType(val code: String) {
    NEW("0"),
    CANCELED("4"),
    REJECTED("8"),
    EXPIRED("C"),
    TRADE("F"),
    ORDER_STATUS("I");

    companion object {
        fun fromCode(code: String): ExecType = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("unknown ExecType $code")
    }
}
