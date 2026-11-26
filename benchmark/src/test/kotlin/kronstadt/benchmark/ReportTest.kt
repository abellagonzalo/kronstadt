package kronstadt.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ReportTest {
    private val nav = task("nav-1", TaskKind.NAVIGATION)
    private val edit = task("edit-1", TaskKind.LOCAL_EDIT)

    private fun result(task: BenchmarkTask, arm: Arm, run: Int, outcome: Outcome, tokens: Tokens, cost: Double?, seconds: Int) =
        RunResult(PlannedSession(task, arm, run), outcome, tokens, cost, seconds.seconds)

    private val results = listOf(
        result(nav, Arm.WITH_KRONSTADT, 1, Outcome.Succeeded, Tokens(100, 1000, 9000, 900), 0.10, 30),
        result(nav, Arm.WITHOUT_KRONSTADT, 1, Outcome.Succeeded, Tokens(200, 2000, 18000, 1800), 0.20, 60),
        result(edit, Arm.WITH_KRONSTADT, 1, Outcome.Failed("timed out after 15m"), Tokens(5, 5, 5, 5), null, 900),
        result(edit, Arm.WITHOUT_KRONSTADT, 1, Outcome.Succeeded, Tokens(10, 10, 10, 10), 0.01, 10),
        result(nav, Arm.WITH_KRONSTADT, 2, Outcome.Succeeded, Tokens(100, 1000, 11000, 900), 0.14, 50),
        result(nav, Arm.WITHOUT_KRONSTADT, 2, Outcome.Failed("check failed"), Tokens(1, 1, 1, 1), 0.05, 70),
    )

    private val header = ReportHeader(
        timestamp = "2026-11-26T20-00-00",
        level = Level.LOW,
        model = "claude-sonnet-5-5",
        timeout = 15.minutes,
        maxTurns = 50,
        target = "square/kotlinpoet 2.4.0 (97e504bb)",
        kronstadt = "abc1234",
        coldStart = 73.seconds,
    )

    @Test
    fun `per task and arm, the success rate and the median and range of successful runs, then the difference between the arms`() {
        val report = Report(header, listOf(nav, edit), results)

        assertEquals(
            """
            |# Kronstadt benchmark 2026-11-26T20-00-00
            |
            |- Level: low. **Smoke test**: 1 run per task and arm, not statistically meaningful.
            |- Model: claude-sonnet-5-5, capped at 15m and 50 turns per session.
            |- Target: square/kotlinpoet 2.4.0 (97e504bb).
            |- Kronstadt: abc1234.
            |- Index Seed cold start, warmed before any timing: 73.0 s.
            |
            |## Results
            |
            |Successful runs only: median (min–max).
            |
            || Task | Kind | Arm | Success | Total tokens | Cost (USD) | Wall-clock |
            ||---|---|---|---|---|---|---|
            || nav-1 | navigation | with Kronstadt | 2/2 | 12,000 (11,000–13,000) | 0.1200 (0.1000–0.1400) | 40.0 s (30.0–50.0 s) |
            || nav-1 | navigation | without Kronstadt | 1/2 | 22,000 (22,000–22,000) | 0.2000 (0.2000–0.2000) | 60.0 s (60.0–60.0 s) |
            || edit-1 | local-edit | with Kronstadt | 0/1 | – | – | – |
            || edit-1 | local-edit | without Kronstadt | 1/1 | 40 (40–40) | 0.0100 (0.0100–0.0100) | 10.0 s (10.0–10.0 s) |
            |
            |## Tokens by kind
            |
            |Successful runs only: median.
            |
            || Task | Arm | Input | Output | Cache reads | Cache writes |
            ||---|---|---|---|---|---|
            || nav-1 | with Kronstadt | 100 | 1,000 | 10,000 | 900 |
            || nav-1 | without Kronstadt | 200 | 2,000 | 18,000 | 1,800 |
            || edit-1 | with Kronstadt | – | – | – | – |
            || edit-1 | without Kronstadt | 10 | 10 | 10 | 10 |
            |
            |## With Kronstadt vs without
            |
            |Difference of the medians; negative means Kronstadt used less. Overall sums the medians of the tasks that succeeded in both arms.
            |
            || Task | Total tokens | Cost | Wall-clock |
            ||---|---|---|---|
            || nav-1 | -45.5% | -40.0% | -33.3% |
            || edit-1 | – | – | – |
            || **Overall** | -45.5% | -40.0% | -33.3% |
            |
            |## Failed runs
            |
            |Counted in the success rate only. Their partial usage, as far as it is known:
            |
            || Session | Reason | Total tokens | Cost (USD) | Wall-clock |
            ||---|---|---|---|---|
            || edit-1-with-1 | timed out after 15m | 20 | unknown | 900.0 s |
            || nav-1-without-2 | check failed | 4 | 0.0500 | 70.0 s |
            |
            """.trimMargin(),
            report.render(),
        )
    }

    @Test
    fun `medium and high are not smoke tests, and a Benchmark without failures says so`() {
        val report = Report(header.copy(level = Level.MEDIUM), listOf(nav), results.filter { it.outcome == Outcome.Succeeded && it.session.task == nav })

        val rendered = report.render()

        assertEquals("- Level: medium, 3 runs per task and arm.", rendered.lines()[2])
        assertEquals("## Failed runs\n\nNone.\n", rendered.substringAfter("| **Overall** | -45.5% | -40.0% | -33.3% |\n\n"))
    }
}
