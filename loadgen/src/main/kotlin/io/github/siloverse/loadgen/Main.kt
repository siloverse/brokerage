package io.github.siloverse.loadgen

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * `loadgen --profile baseline --duration 60 --host 127.0.0.1 --port 10402 --out build/loadgen`
 *
 * One tool, one baseline profile, reused unchanged for every comparison in the plan. The
 * report is printed and written as markdown so a run can be pasted into docs/numbers.md.
 */
fun main(args: Array<String>) {
    val opts = args.toList().chunked(2).associate { (k, v) -> k.removePrefix("--") to v }
    val profile = Profile.named(opts["profile"] ?: "baseline").let { p -> opts["scale"]?.toDouble()?.let { p.scaled(it) } ?: p }
    val duration = Duration.ofSeconds((opts["duration"] ?: Profile.DEFAULT_DURATION.seconds.toString()).toLong())
    val host = opts["host"] ?: "127.0.0.1"
    val port = (opts["port"] ?: "10402").toInt()
    val out = Path.of(opts["out"] ?: "build/loadgen")

    val target = FixVenueTarget(host, port)
    val report = try {
        target.awaitConnected()
        System.err.println("connected to ${target.description}; running '${profile.name}' for ${duration.seconds} s")
        runBlocking { OpenLoopScheduler(profile, target).run(duration) }
    } finally {
        target.close()
    }

    val markdown = report.markdown()
    println(markdown)
    Files.createDirectories(out)
    val file = out.resolve("${profile.name}-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))}.md")
    Files.writeString(file, markdown)
    System.err.println("written $file")
}
