@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.benchmark

import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.useLines
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * Runs `claude` for real. Each session's stream of JSON events is saved to `<session>.jsonl` in [out], next to the
 * report; its stderr to `<session>.stderr.log`.
 */
internal class ClaudeAgent(
    private val out: Path,
    private val kronstadt: Path,
    private val kotlinInstructions: String,
    private val model: String,
    private val maxTurns: Int,
    private val timeout: Duration,
    private val parentEnvironment: Map<String, String> = System.getenv(),
) : Agent {
    override fun run(session: PlannedSession, worktree: Path): AgentRun {
        val configDir = out.resolve("claude-config").resolve(session.name).createDirectories()
        val mcpConfig = if (session.arm == Arm.WITH_KRONSTADT) {
            configDir.resolve("CLAUDE.md").writeText(kotlinInstructions)
            out.resolve("${session.name}.mcp.json").also { it.writeText(ClaudeSession.mcpConfig(kronstadt, worktree)) }
        } else {
            null
        }
        val raw = out.resolve("${session.name}.jsonl")
        try {
            val run = Processes.run(
                ClaudeSession.arguments(session.task.prompt, model, maxTurns, mcpConfig),
                dir = worktree,
                stdout = raw,
                stderr = out.resolve("${session.name}.stderr.log"),
                environment = ClaudeSession.environment(parentEnvironment, configDir),
                timeout = timeout,
            )
            return AgentRun(raw.useLines { StreamJson.parse(it) }, run.wallClock, run.timedOut)
        } finally {
            // Only the stream of events is kept: the config dir is Claude Code's own state.
            configDir.deleteRecursively()
        }
    }
}

/** Runs a Benchmark Task's `check`, logging its output to `<session>.check.log` in [out]. */
internal class ScriptChecks(private val out: Path, private val timeout: Duration = 20.minutes) : Checks {
    override fun passes(session: PlannedSession, worktree: Path, reply: String): Boolean =
        passes(session.task, worktree, reply, out.resolve("${session.name}.check.log"))

    fun passes(task: BenchmarkTask, worktree: Path, reply: String, log: Path): Boolean {
        val run = Processes.run(listOf(task.dir.resolve("check").toString()), worktree, stdout = log, stdin = reply, timeout = timeout)
        return run.exitCode == 0
    }
}

/**
 * Checks the checks, without `claude`: each task's check must fail on the unchanged run worktree, and pass on its
 * reference solution, `solution.patch` applied to the worktree or `solution.md` as the reply.
 */
internal class TaskVerification(
    private val worktrees: RunWorktrees,
    private val checks: ScriptChecks,
    private val out: Path,
    private val progress: (String) -> Unit,
) {
    /** Returns whether every task's check behaves. */
    fun verify(tasks: List<BenchmarkTask>): Boolean = tasks.map { task ->
        val unchanged = check(task, "unchanged") { "" }
        val solved = check(task, "solved") { worktree ->
            val patch = task.dir.resolve("solution.patch")
            if (patch.exists()) {
                Processes.output("git", "apply", patch.toString(), dir = worktree)
                ""
            } else {
                task.dir.resolve("solution.md").readText()
            }
        }
        val ok = !unchanged && solved
        progress("${if (ok) "ok  " else "BAD "} ${task.id}: unchanged ${verdict(unchanged)}, solution ${verdict(solved)}")
        ok
    }.all { it }

    private fun check(task: BenchmarkTask, state: String, prepare: (Path) -> String): Boolean {
        val name = "verify-${task.id}-$state"
        return worktrees.use(name) { worktree -> checks.passes(task, worktree, prepare(worktree), out.resolve("$name.check.log")) }
    }

    private fun verdict(passed: Boolean) = if (passed) "passes" else "fails"
}
