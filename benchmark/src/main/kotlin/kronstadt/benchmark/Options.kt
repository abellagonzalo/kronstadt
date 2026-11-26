package kronstadt.benchmark

import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** What the user chose on the command line. */
internal data class Options(
    val level: Level,
    val model: String,
    val timeout: Duration,
    val maxTurns: Int,
    val verifyTasks: Boolean,
    val checkSeed: Boolean = false,
)

internal const val USAGE: String = """Usage: benchmark [--level low|medium|high] [--model <id>] [--timeout-minutes <n>] [--max-turns <n>] [--verify-tasks | --check-seed]

Runs Claude Code on the Benchmark Tasks with and without Kronstadt, one session at a time, and reports success rate,
tokens, cost and wall-clock time under build/benchmark/<timestamp>/. Don't run it while other heavy builds run.

  --level <level>        low (default): 3 tasks x 1 run x 2 arms = 6 sessions, a smoke test;
                         medium: 6 x 3 x 2 = 36 sessions; high: 6 x 5 x 2 = 60 sessions
  --model <id>           the model, pinned by ID (default: claude-sonnet-5-5)
  --timeout-minutes <n>  a session's wall-clock cap; one that hits it fails (default: 15)
  --max-turns <n>        a session's turn cap; one that hits it fails (default: 50)
  --verify-tasks         don't run claude: check that each task's check fails on the unchanged worktree and passes
                         on its reference solution
  --check-seed           don't run claude: warm and freeze the Index Seed, then start Kronstadt from it once, as a
                         session with Kronstadt would, and check that its refresh is discarded"""

/** Parses the command line; throws [IllegalArgumentException] with the problem for a usage error. */
internal fun parseOptions(args: List<String>): Options {
    var options = Options(Level.LOW, "claude-sonnet-5-5", 15.minutes, 50, verifyTasks = false)
    val queue = ArrayDeque(args)
    fun value(flag: String) = queue.removeFirstOrNull() ?: throw IllegalArgumentException("$flag needs a value")
    fun positive(flag: String): Int {
        val text = value(flag)
        return text.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$flag takes a positive whole number, got '$text'")
    }
    while (queue.isNotEmpty()) {
        options = when (val flag = queue.removeFirst()) {
            "--level" -> options.copy(level = Level.of(value(flag)))
            "--model" -> options.copy(model = value(flag))
            "--timeout-minutes" -> options.copy(timeout = positive(flag).minutes)
            "--max-turns" -> options.copy(maxTurns = positive(flag))
            "--verify-tasks" -> options.copy(verifyTasks = true)
            "--check-seed" -> options.copy(checkSeed = true)
            else -> throw IllegalArgumentException("unknown argument: $flag")
        }
    }
    return options
}
