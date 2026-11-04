package io.github.siloverse.venue.admin

import io.github.siloverse.venue.fix.VenueFixServer
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * The chaos knobs over HTTP, on the venue's admin port, so a drill never needs a restart.
 *
 * ```
 * curl -s localhost:10401/admin/chaos
 * curl -s -X PUT localhost:10401/admin/chaos -H 'content-type: application/json' -d '{"duplicateReportRate":1.0}'
 * curl -s -X POST localhost:10401/admin/chaos/drop-connection
 * curl -s -X POST localhost:10401/admin/chaos/reset-sequences
 * curl -s -X POST localhost:10401/admin/chaos/price/ACME/9950
 * ```
 */
@RestController
@RequestMapping("/admin/chaos")
class ChaosController(private val server: VenueFixServer) {

    /** Every field optional; absent means unchanged. */
    data class KnobsPatch(
        val fillDelayMillis: Long? = null,
        val partialFills: Int? = null,
        val rejectRate: Double? = null,
        val duplicateReportRate: Double? = null,
        val phantomReportRate: Double? = null,
    )

    @GetMapping
    fun current(): Map<String, Any> = server.engine.knobs.snapshot() + mapOf(
        "sessionActive" to server.driver.isActive,
        "outboundQueueDepth" to server.driver.queueDepth,
        "outboundQueueMaxDepth" to server.driver.maxQueueDepth.get(),
        "recentEvents" to server.events.takeLast(10).map { it.toString() },
    )

    @PutMapping
    fun update(@RequestBody patch: KnobsPatch): Map<String, Any> {
        val knobs = server.engine.knobs
        patch.fillDelayMillis?.let { knobs.fillDelay = Duration.ofMillis(it) }
        patch.partialFills?.let { knobs.partialFills = it }
        patch.rejectRate?.let { knobs.rejectRate = it }
        patch.duplicateReportRate?.let { knobs.duplicateReportRate = it }
        patch.phantomReportRate?.let { knobs.phantomReportRate = it }
        knobs.validate()
        return knobs.snapshot()
    }

    @PostMapping("/drop-connection")
    fun dropConnection(): Map<String, Any> {
        server.dropConnection()
        return mapOf("dropped" to true)
    }

    @PostMapping("/reset-sequences")
    fun resetSequences(): Map<String, Any> {
        server.resetSequences()
        return mapOf("resetOnNextLogon" to true)
    }

    @PostMapping("/price/{symbol}/{mid}")
    fun pinPrice(@PathVariable symbol: String, @PathVariable mid: Long): Map<String, Any> {
        server.pinPrice(symbol, mid)
        return mapOf("symbol" to symbol, "mid" to mid)
    }
}
