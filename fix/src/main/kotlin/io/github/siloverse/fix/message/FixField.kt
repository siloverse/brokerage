package io.github.siloverse.fix.message

/** One `tag=value` pair. Values are always strings on the wire; typed reads live on [FixMessage]. */
data class FixField(val tag: Int, val value: String)
