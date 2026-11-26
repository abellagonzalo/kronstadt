package kronstadt.benchmark

import java.nio.file.Path
import java.time.Instant
import java.util.Locale
import kotlin.time.Duration

/** Run worktrees of the target project at its pinned commit: one per session, deleted afterwards. */
internal interface RunWorktrees {
    fun create(name: String): Path

    fun remove(worktree: Path)
}

/** Runs [action] in a new run worktree named [name], and removes it afterwards, whatever happens. */
internal fun <T> RunWorktrees.use(name: String, action: (Path) -> T): T {
    val worktree = create(name)
    try {
        return action(worktree)
    } finally {
        remove(worktree)
    }
}

/** The target project's Index Seed, frozen for the whole Benchmark so that runs with Kronstadt are repeatable. */
internal interface IndexSeed {
    /** Indexes [worktree] cold through Kronstadt, saves the seed that leaves, and returns how long indexing took. */
    fun warm(worktree: Path): Duration

    /** Puts the saved seed back, so the next session with Kronstadt starts from it. */
    fun restore()

    /** Waits until a refresh that a session started at [since] triggered has finished, if it triggered one. */
    fun awaitRefresh(since: Instant)
}

/** What one `claude` session printed, how long it ran from launch to exit, and whether it was killed at the timeout. */
internal data class AgentRun(val output: SessionOutput, val wallClock: Duration, val timedOut: Boolean)

/** Runs one session of `claude` in a run worktree. */
internal fun interface Agent {
    fun run(session: PlannedSession, worktree: Path): AgentRun
}

/** Runs a Benchmark Task's check in the run worktree after the session, with the agent's final reply on stdin. */
internal fun interface Checks {
    fun passes(session: PlannedSession, worktree: Path, reply: String): Boolean
}

/** One Benchmark: warms and freezes the Index Seed, then runs the sessions one at a time. */
internal class Benchmark(
    private val worktrees: RunWorktrees,
    private val seed: IndexSeed,
    private val agent: Agent,
    private val checks: Checks,
    private val timeout: Duration,
    private val maxTurns: Int,
    private val clock: () -> Instant = Instant::now,
    private val progress: (String) -> Unit,
) {
    /** Warms the Index Seed in a run worktree of its own, before any timing, and returns the cold-start time. */
    fun warmSeed(): Duration = worktrees.use("warm-seed") { seed.warm(it) }

    /** Runs [sessions] one at a time, in order, never in parallel: concurrent kotlin-lsp and Gradle work distorts timings. */
    fun run(sessions: List<PlannedSession>): List<RunResult> = sessions.mapIndexed { index, session ->
        progress("[${index + 1}/${sessions.size}] ${session.task.id}, ${session.arm.label}, run ${session.run}")
        run(session).also { progress("  ${describe(it)}") }
    }

    private fun run(session: PlannedSession): RunResult {
        val withKronstadt = session.arm == Arm.WITH_KRONSTADT
        if (withKronstadt) seed.restore()
        return worktrees.use(session.name) { worktree ->
            val started = clock()
            val agentRun = try {
                agent.run(session, worktree)
            } finally {
                // The session's refresh would replace the frozen seed: let it finish, even if the session broke, so the
                // next restore discards it rather than racing it.
                if (withKronstadt) seed.awaitRefresh(started)
            }
            val output = agentRun.output
            RunResult(session, outcome(session, worktree, agentRun), output.tokens, output.result?.costUsd, agentRun.wallClock)
        }
    }

    /** A run that hit a cap or ended in an error fails without a check. */
    private fun outcome(session: PlannedSession, worktree: Path, run: AgentRun): Outcome {
        val result = run.output.result
        return when {
            run.timedOut -> Outcome.Failed("timed out after $timeout")
            result == null -> Outcome.Failed("claude exited without a result")
            result.subtype == "error_max_turns" -> Outcome.Failed("hit the cap of $maxTurns turns")
            result.isError || result.subtype != "success" -> Outcome.Failed("claude ended with ${result.subtype}")
            !checks.passes(session, worktree, result.reply.orEmpty()) -> Outcome.Failed("check failed")
            else -> Outcome.Succeeded
        }
    }

    private fun describe(result: RunResult): String {
        val outcome = when (val o = result.outcome) {
            Outcome.Succeeded -> "succeeded"
            is Outcome.Failed -> "failed: ${o.reason}"
        }
        val cost = result.costUsd?.let { "$%.4f".format(Locale.US, it) } ?: "cost unknown"
        return "$outcome; ${result.tokens.total} tokens, $cost, ${result.wallClock.inWholeSeconds} s"
    }
}
