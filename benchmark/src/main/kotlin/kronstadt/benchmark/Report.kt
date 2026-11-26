package kronstadt.benchmark

import java.util.Locale
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** How a run ended: it succeeded only if `claude` finished within its caps and the task's check passed. */
internal sealed interface Outcome {
    data object Succeeded : Outcome

    data class Failed(val reason: String) : Outcome
}

/** One session's measurements. [costUsd] is what Claude Code reported, null if it was killed before reporting it. */
internal data class RunResult(
    val session: PlannedSession,
    val outcome: Outcome,
    val tokens: Tokens,
    val costUsd: Double?,
    val wallClock: Duration,
)

/** What the report says about the Benchmark as a whole. */
internal data class ReportHeader(
    val timestamp: String,
    val level: Level,
    val model: String,
    val timeout: Duration,
    val maxTurns: Int,
    val target: String,
    val kronstadt: String,
    val coldStart: Duration,
)

/**
 * The Benchmark's report, in markdown: [results] of [tasks], in the order they ran.
 *
 * Failed runs count towards the success rate only; the token, cost and time figures are the median and range of the
 * successful runs. The difference between the arms compares medians, per task and overall, where overall sums the
 * medians of the tasks that succeeded at least once in both arms.
 */
internal class Report(private val header: ReportHeader, private val tasks: List<BenchmarkTask>, private val results: List<RunResult>) {
    fun render(): String = buildString {
        appendLine("# Kronstadt benchmark ${header.timestamp}")
        appendLine()
        appendHeader()
        appendLine()
        appendResults()
        appendLine()
        appendTokensByKind()
        appendLine()
        appendDifferences()
        appendLine()
        appendFailures()
    }

    private fun StringBuilder.appendHeader() {
        val runs = header.level.runsPerTaskAndArm
        val perTaskAndArm = "$runs run${if (runs == 1) "" else "s"} per task and arm"
        if (header.level == Level.LOW) {
            appendLine("- Level: low. **Smoke test**: $perTaskAndArm, not statistically meaningful.")
        } else {
            appendLine("- Level: ${header.level.label}, $perTaskAndArm.")
        }
        appendLine("- Model: ${header.model}, capped at ${header.timeout} and ${header.maxTurns} turns per session.")
        appendLine("- Target: ${header.target}.")
        appendLine("- Kronstadt: ${header.kronstadt}.")
        appendLine("- Index Seed cold start, warmed before any timing: ${seconds(header.coldStart.toDouble(DurationUnit.SECONDS))}.")
    }

    private fun StringBuilder.appendResults() {
        appendLine("## Results")
        appendLine()
        appendLine("Successful runs only: median (min–max).")
        appendLine()
        appendLine("| Task | Kind | Arm | Success | Total tokens | Cost (USD) | Wall-clock |")
        appendLine("|---|---|---|---|---|---|---|")
        for (task in tasks) {
            for (arm in Arm.entries) {
                val runs = runsOf(task, arm)
                val successes = runs.filter { it.outcome == Outcome.Succeeded }
                val cells = Metric.entries.map { metric -> spread(successes, metric)?.let { metric.formatSpread(it) } ?: NONE }
                appendLine("| ${task.id} | ${task.kind.label} | ${arm.label} | ${successes.size}/${runs.size} | ${cells.joinToString(" | ")} |")
            }
        }
    }

    private fun StringBuilder.appendTokensByKind() {
        appendLine("## Tokens by kind")
        appendLine()
        appendLine("Successful runs only: median.")
        appendLine()
        appendLine("| Task | Arm | Input | Output | Cache reads | Cache writes |")
        appendLine("|---|---|---|---|---|---|")
        val kinds = listOf(Tokens::input, Tokens::output, Tokens::cacheRead, Tokens::cacheWrite)
        for (task in tasks) {
            for (arm in Arm.entries) {
                val successes = runsOf(task, arm).filter { it.outcome == Outcome.Succeeded }
                val cells = kinds.map { kind ->
                    if (successes.isEmpty()) NONE else tokens(median(successes.map { kind(it.tokens).toDouble() }))
                }
                appendLine("| ${task.id} | ${arm.label} | ${cells.joinToString(" | ")} |")
            }
        }
    }

    private fun StringBuilder.appendDifferences() {
        appendLine("## With Kronstadt vs without")
        appendLine()
        appendLine(
            "Difference of the medians; negative means Kronstadt used less. " +
                "Overall sums the medians of the tasks that succeeded in both arms.",
        )
        appendLine()
        appendLine("| Task | Total tokens | Cost | Wall-clock |")
        appendLine("|---|---|---|---|")
        val overall = Metric.entries.associateWith { doubleArrayOf(0.0, 0.0) }
        for (task in tasks) {
            val cells = Metric.entries.map { metric ->
                val with = median(task, Arm.WITH_KRONSTADT, metric)
                val without = median(task, Arm.WITHOUT_KRONSTADT, metric)
                if (with == null || without == null) return@map NONE
                overall.getValue(metric)[0] += with
                overall.getValue(metric)[1] += without
                percent(with, without)
            }
            appendLine("| ${task.id} | ${cells.joinToString(" | ")} |")
        }
        val overallCells = Metric.entries.map { metric ->
            val (with, without) = overall.getValue(metric)
            if (without == 0.0) NONE else percent(with, without)
        }
        appendLine("| **Overall** | ${overallCells.joinToString(" | ")} |")
    }

    private fun StringBuilder.appendFailures() {
        appendLine("## Failed runs")
        appendLine()
        val failures = results.filter { it.outcome is Outcome.Failed }
        if (failures.isEmpty()) {
            appendLine("None.")
            return
        }
        appendLine("Counted in the success rate only. Their partial usage, as far as it is known:")
        appendLine()
        appendLine("| Session | Reason | Total tokens | Cost (USD) | Wall-clock |")
        appendLine("|---|---|---|---|---|")
        for (failure in failures) {
            val reason = (failure.outcome as Outcome.Failed).reason
            val cost = failure.costUsd?.let(::usd) ?: "unknown"
            val time = seconds(failure.wallClock.toDouble(DurationUnit.SECONDS))
            appendLine("| ${failure.session.name} | $reason | ${tokens(failure.tokens.total.toDouble())} | $cost | $time |")
        }
    }

    private fun runsOf(task: BenchmarkTask, arm: Arm): List<RunResult> =
        results.filter { it.session.task.id == task.id && it.session.arm == arm }

    private fun median(task: BenchmarkTask, arm: Arm, metric: Metric): Double? =
        spread(runsOf(task, arm).filter { it.outcome == Outcome.Succeeded }, metric)?.median

    /** A figure the report gives per run, and how it prints. */
    private enum class Metric(val of: (RunResult) -> Double?, val format: (Double) -> String) {
        TOTAL_TOKENS({ it.tokens.total.toDouble() }, ::tokens),
        COST({ it.costUsd }, ::usd),
        WALL_CLOCK({ it.wallClock.toDouble(DurationUnit.SECONDS) }, { "%.1f".format(Locale.US, it) }),
        ;

        fun formatSpread(spread: Spread): String {
            val unit = if (this == WALL_CLOCK) " s" else ""
            return "${format(spread.median)}$unit (${format(spread.min)}–${format(spread.max)}$unit)"
        }
    }

    private data class Spread(val median: Double, val min: Double, val max: Double)

    private companion object {
        const val NONE = "–"

        fun spread(runs: List<RunResult>, metric: Metric): Spread? {
            val values = runs.mapNotNull(metric.of)
            if (values.isEmpty()) return null
            return Spread(median(values), values.min(), values.max())
        }

        fun median(values: List<Double>): Double {
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }

        fun percent(with: Double, without: Double): String =
            if (without == 0.0) NONE else "%+.1f%%".format(Locale.US, (with - without) / without * 100)

        fun tokens(value: Double): String = "%,d".format(Locale.US, value.roundToLong())

        fun usd(value: Double): String = "%.4f".format(Locale.US, value)

        fun seconds(value: Double): String = "%.1f s".format(Locale.US, value)
    }
}
