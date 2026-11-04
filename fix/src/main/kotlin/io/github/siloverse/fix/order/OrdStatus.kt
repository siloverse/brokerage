package io.github.siloverse.fix.order

/** OrdStatus (39): where the order stands after this report. */
enum class OrdStatus(val code: String) {
    NEW("0"),
    PARTIALLY_FILLED("1"),
    FILLED("2"),
    CANCELED("4"),
    REJECTED("8"),
    EXPIRED("C");

    val isTerminal: Boolean get() = this == FILLED || this == CANCELED || this == REJECTED || this == EXPIRED

    companion object {
        fun fromCode(code: String): OrdStatus = entries.firstOrNull { it.code == code }
            ?: throw IllegalArgumentException("unknown OrdStatus $code")
    }
}
