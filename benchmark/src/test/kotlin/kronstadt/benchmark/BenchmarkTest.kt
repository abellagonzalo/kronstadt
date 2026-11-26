package kronstadt.benchmark

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class BenchmarkTest {
    private val events = mutableListOf<String>()
    private val outputs = mutableMapOf<String, AgentRun>()
    private val failingChecks = mutableSetOf<String>()

    private val worktrees = object : RunWorktrees {
        override fun create(name: String): Path = Path.of("/runs", name).also { events += "create $name" }
        override fun remove(worktree: Path) { events += "remove ${worktree.fileName}" }
    }
    private val seed = object : IndexSeed {
        override fun warm(worktree: Path): Duration = 73.seconds.also { events += "warm in ${worktree.fileName}" }
        override fun restore() { events += "restore seed" }
        override fun awaitRefresh(since: Instant) { events += "await refresh" }
    }
    private val agent = Agent { session, worktree ->
        events += "claude ${worktree.fileName}"
        outputs[session.name] ?: run(reply = "reply of ${session.name}")
    }
    private val checks = Checks { session, worktree, reply ->
        events += "check ${worktree.fileName}: $reply"
        session.name !in failingChecks
    }

    private val benchmark = Benchmark(worktrees, seed, agent, checks, timeout = 15.minutes, maxTurns = 50, progress = {})

    private fun run(
        subtype: String? = "success",
        reply: String? = "done",
        isError: Boolean = false,
        timedOut: Boolean = false,
        tokens: Tokens = Tokens(1, 2, 3, 4),
        cost: Double? = 0.5,
    ) = AgentRun(
        SessionOutput(subtype?.let { SessionResult(it, isError, reply, cost, turns = 3) }, tokens),
        wallClock = 42.seconds,
        timedOut = timedOut,
    )

    @Test
    fun `the seed is warmed in a run worktree of its own, untimed by the sessions`() {
        assertEquals(73.seconds, benchmark.warmSeed())
        assertEquals(listOf("create warm-seed", "warm in warm-seed", "remove warm-seed"), events)
    }

    @Test
    fun `each session runs in a new run worktree, and with Kronstadt starts from the frozen seed and discards the refresh it triggers`() {
        val nav = task("nav-1")

        benchmark.run(schedule(listOf(nav), runs = 1))

        assertEquals(
            listOf(
                "restore seed", "create nav-1-with-1", "claude nav-1-with-1", "await refresh",
                "check nav-1-with-1: reply of nav-1-with-1", "remove nav-1-with-1",
                "create nav-1-without-1", "claude nav-1-without-1",
                "check nav-1-without-1: reply of nav-1-without-1", "remove nav-1-without-1",
            ),
            events,
        )
    }

    @Test
    fun `a run succeeds only if claude finished within its caps and the check passed`() {
        val sessions = schedule(listOf("ok", "check", "timeout", "turns", "error", "no-result").map { task(it) }, runs = 1)
            .filter { it.arm == Arm.WITHOUT_KRONSTADT }
        failingChecks += "check-without-1"
        outputs["timeout-without-1"] = run(subtype = null, timedOut = true, cost = null)
        outputs["turns-without-1"] = run(subtype = "error_max_turns", reply = null, cost = 1.25)
        outputs["error-without-1"] = run(subtype = "error_during_execution", reply = null, isError = true)
        outputs["no-result-without-1"] = run(subtype = null)

        val results = benchmark.run(sessions)

        assertEquals(
            listOf(
                Outcome.Succeeded,
                Outcome.Failed("check failed"),
                Outcome.Failed("timed out after 15m"),
                Outcome.Failed("hit the cap of 50 turns"),
                Outcome.Failed("claude ended with error_during_execution"),
                Outcome.Failed("claude exited without a result"),
            ),
            results.map { it.outcome },
        )
        assertEquals(listOf("ok", "check"), events.filter { it.startsWith("check") }.map { it.removePrefix("check ").substringBefore("-") })
        val capped = results[3]
        assertEquals(RunResult(sessions[3], Outcome.Failed("hit the cap of 50 turns"), Tokens(1, 2, 3, 4), 1.25, 42.seconds), capped)
    }

    @Test
    fun `the run worktree is removed even when a session breaks`() {
        val broken = Benchmark(worktrees, seed, { _, _ -> error("claude not found") }, checks, 15.minutes, 50, progress = {})

        runCatching { broken.run(schedule(listOf(task("nav-1")), runs = 1).drop(1)) }

        assertEquals(listOf("create nav-1-without-1", "remove nav-1-without-1"), events)
    }
}
