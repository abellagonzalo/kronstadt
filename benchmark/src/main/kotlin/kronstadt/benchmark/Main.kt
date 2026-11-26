package kronstadt.benchmark

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.exitProcess
import kotlin.time.DurationUnit

/**
 * Dev-only: `./gradlew :benchmark:run --args="--level medium"`. Runs from the repository root, where Gradle starts it.
 *
 * The target project's clone and the run worktrees live in `~/Library/Caches/kronstadt-benchmark/`, outside this
 * repository, so that Claude Code finds no CLAUDE.md above them; the report goes to `build/benchmark/<timestamp>/`.
 */
public fun main(args: Array<String>) {
    if ("-h" in args || "--help" in args) {
        println(USAGE)
        return
    }
    val options = try {
        parseOptions(args.toList())
    } catch (e: IllegalArgumentException) {
        usage(e.message ?: e.toString())
    }
    val repository = Path.of("").toAbsolutePath()
    val tasks = BenchmarkTasks.load(repository.resolve("benchmark/tasks"))
    val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH-mm-ss"))
    val out = repository.resolve("build/benchmark/$timestamp").createDirectories()
    val work = Path.of(System.getProperty("user.home"), "Library", "Caches", "kronstadt-benchmark")
    instructionsAbove(work)?.let { fail("$it would reach every session; the run worktrees must have no CLAUDE.md above them") }

    val target = TargetProject.KOTLINPOET
    val worktrees = GitRunWorktrees(work.resolve("kotlinpoet-${target.tag}"), target, work.resolve("runs"))
    worktrees.ensureClone()
    val progress: (String) -> Unit = { println(it) }

    if (options.verifyTasks) {
        val verified = TaskVerification(worktrees, ScriptChecks(out), out, progress).verify(tasks)
        println(if (verified) "Every task's check fails unchanged and passes on its solution." else "Some checks misbehave; logs in $out")
        exitProcess(if (verified) 0 else 1)
    }

    val kronstadt = repository.resolve("server/build/install/kronstadt/bin/kronstadt")
    if (!kronstadt.exists()) fail("$kronstadt is missing: run `./gradlew installDist`, or run the benchmark through Gradle")
    val seed = FrozenSeed(kronstadt, worktrees.gitCommonDir, work.resolve("frozen-seed"), target.warmQuery, out)

    if (options.checkSeed) exitProcess(if (checkSeed(seed, worktrees, out)) 0 else 1)

    if (!ClaudeSession.hasCredentials(System.getenv())) {
        fail(
            "set ${ClaudeSession.API_KEY} or ${ClaudeSession.OAUTH_TOKEN} (from `claude setup-token`): each session gets a " +
                "clean config dir, which holds no login",
        )
    }
    val instructions = KotlinInstructions.fromReadme(repository.resolve("README.md").readText())

    val agent = ClaudeAgent(out, kronstadt, instructions, options.model, options.maxTurns, options.timeout)
    val benchmark = Benchmark(worktrees, seed, agent, ScriptChecks(out), options.timeout, options.maxTurns, progress = progress)

    val selected = options.level.tasks(tasks)
    val runs = options.level.runsPerTaskAndArm
    val sessions = schedule(selected, runs)
    println("Level ${options.level.label}: ${selected.size} tasks x $runs runs x 2 arms = ${sessions.size} sessions, one at a time.")
    println("Warming the Index Seed (untimed)...")
    val coldStart = benchmark.warmSeed()
    println("Index Seed cold start: ${coldStart.inWholeSeconds} s")
    val results = benchmark.run(sessions)

    val header = ReportHeader(
        timestamp, options.level, options.model, options.timeout, options.maxTurns, target.description,
        kronstadtVersion(repository), coldStart,
    )
    val report = Report(header, selected, results).render()
    out.resolve("report.md").writeText(report)
    out.resolve("results.json").writeText(resultsJson(results))
    println()
    print(report)
    println("\nReport, raw session output and check logs: $out")
}

/**
 * Warms and freezes the Index Seed, then goes once through what a session with Kronstadt does to it, without `claude`:
 * restore it, start Kronstadt from it in a new run worktree, await the refresh its exit starts, and restore it again.
 * Returns whether Kronstadt started from the seed and the restore discarded the refresh.
 */
private fun checkSeed(seed: FrozenSeed, worktrees: RunWorktrees, out: Path): Boolean {
    println("Warming the Index Seed...")
    val coldStart = worktrees.use("warm-seed") { seed.warm(it) }
    println("Cold start: ${coldStart.inWholeSeconds} s; seed saved")
    seed.restore()
    return worktrees.use("check-seed") { worktree ->
        val log = out.resolve("check-seed.kronstadt.log")
        val started = Instant.now()
        val ready = seed.serve(worktree, log)
        seed.awaitRefresh(started)
        val seeded = "from the repo's Index Seed" in log.readText()
        val refreshedOver = !seed.isFrozen()
        seed.restore()
        val restored = seed.isFrozen()
        println("Started from the seed: $seeded, ready in ${ready.inWholeSeconds} s (log: $log)")
        println("Its refresh replaced the seed: $refreshedOver; the restore put the frozen seed back: $restored")
        seeded && restored
    }
}

/** The first CLAUDE.md or CLAUDE.local.md in [dir] or above it, which Claude Code would load in every session. */
private fun instructionsAbove(dir: Path): Path? =
    generateSequence(dir) { it.parent }
        .flatMap { sequenceOf(it.resolve("CLAUDE.md"), it.resolve("CLAUDE.local.md")) }
        .firstOrNull { it.exists() }

private fun kronstadtVersion(repository: Path): String {
    val commit = Processes.output("git", "rev-parse", "--short", "HEAD", dir = repository)
    val dirty = Processes.output("git", "status", "--porcelain", dir = repository).isNotEmpty()
    return if (dirty) "$commit with uncommitted changes" else commit
}

/** Every run, failed ones included, with all four kinds of tokens and the cost Claude Code reported. */
private fun resultsJson(results: List<RunResult>): String = buildJsonArray {
    for (result in results) {
        addJsonObject {
            put("session", result.session.name)
            put("task", result.session.task.id)
            put("kind", result.session.task.kind.label)
            put("arm", result.session.arm.label)
            put("run", result.session.run)
            when (val outcome = result.outcome) {
                Outcome.Succeeded -> put("success", true)
                is Outcome.Failed -> { put("success", false); put("failure", outcome.reason) }
            }
            putJsonObject("tokens") {
                put("input", result.tokens.input)
                put("output", result.tokens.output)
                put("cacheRead", result.tokens.cacheRead)
                put("cacheWrite", result.tokens.cacheWrite)
                put("total", result.tokens.total)
            }
            if (result.costUsd != null) put("costUsd", result.costUsd) else put("costUsd", JsonNull)
            put("wallClockSeconds", result.wallClock.toDouble(DurationUnit.SECONDS))
        }
    }
}.toString()

private fun usage(problem: String): Nothing {
    System.err.println("$problem\n\n$USAGE")
    exitProcess(2)
}

private fun fail(problem: String): Nothing {
    System.err.println("benchmark: $problem")
    exitProcess(1)
}
