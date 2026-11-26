package kronstadt.lsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolKind
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import java.util.Collections
import kotlin.io.path.appendText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Seam 2 (#37): pins what kotlin-lsp sends while it re-imports the fixture after its build script changed, and that
 * Kronstadt never reports ready meanwhile. Its own kotlin-lsp, because the re-import changes the fixture's build.
 *
 * Kronstadt sends no `workspace/didChangeWatchedFiles`: kotlin-lsp watches the build files itself.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReimportContractTest {
    @JvmField
    @RegisterExtension
    internal val kotlinLsp = KotlinLspHarness()
    private val project get() = kotlinLsp.project
    private val lsp get() = kotlinLsp.lsp

    /** How long kotlin-lsp must have said nothing before the change; well above the startup gaps seen under load (#101). */
    private val quiet = 3.seconds

    /** Whether Kronstadt reported ready when asked at [at]; [answeredAt] is when the answer came back. */
    private class ReadinessSample(val at: ComparableTimeMark, val answeredAt: ComparableTimeMark, val ready: Boolean)

    @Test
    fun `a build script change re-imports with no import phase but FINISHED, through gaps the settle period covers`() = runBlocking {
        assertEquals(Readiness.Ready, lsp.awaitReady(5.minutes))

        val signals = Collections.synchronizedList(mutableListOf<ReadinessSignal>())
        val samples = Collections.synchronizedList(mutableListOf<ReadinessSample>())
        val subscribed = TimeSource.Monotonic.markNow()
        val recorder = launch(Dispatchers.Default) { lsp.readinessSignals.collect { signals += it } }

        // kotlin-lsp can keep indexing after Kronstadt is first ready, with gaps longer than the settle period, and while
        // other kotlin-lsp or Gradle runs are busy on the machine it even re-imports on its own (#101). The change must
        // find kotlin-lsp quiet, or the re-import below mixes with that work. This also gives the recorder time to subscribe.
        withTimeout(5.minutes) {
            while ((signals.lastOrNull()?.at ?: subscribed).elapsedNow() < quiet) delay(50.milliseconds)
        }
        assertEquals(Readiness.Ready, lsp.awaitReady(5.minutes))

        val sampler = launch(Dispatchers.Default) {
            while (true) {
                val at = TimeSource.Monotonic.markNow()
                val ready = lsp.awaitReady(Duration.ZERO) == Readiness.Ready
                samples += ReadinessSample(at, TimeSource.Monotonic.markNow(), ready)
                delay(5.milliseconds)
            }
        }
        delay(100.milliseconds) // the sampler is running before the change

        val edited = TimeSource.Monotonic.markNow()
        project.resolve("build.gradle.kts").appendText(
            "\ndependencies {\n    implementation(\"org.jetbrains:annotations:24.1.0\")\n}\n",
        )
        withTimeout(5.minutes) {
            while (!importFinished(signals.toList().filter { it.at > edited }.map { it.text })) delay(50.milliseconds)
        }
        assertEquals(Readiness.Ready, lsp.awaitReady(5.minutes))
        val readyAt = TimeSource.Monotonic.markNow()
        sampler.cancel()
        recorder.cancel()

        // The re-import: every signal from the change until Kronstadt reported ready again.
        val seen = signals.toList().filter { it.at > edited && it.at < readyAt }
        val texts = seen.map { it.text }
        val importing = texts.indexOf("begin Importing")
        val importEnd = texts.indexOf("end Importing")
        assertTrue(importing >= 0 && importEnd > importing, "no Importing after the change\n" + timeline(edited, signals.toList(), emptyList()))

        // The import state never leaves FINISHED: open progress is the only sign of a re-import.
        assertEquals(listOf("import FINISHED"), texts.filter { it.startsWith("import ") }, "signals: $texts")
        assertTrue(texts.indexOf("import FINISHED") > importing, "signals: $texts")

        // Not ready at any point from the first sign of the re-import until its last progress ended.
        val busyFrom = seen.first().at
        val busyUntil = seen.last().at
        val readyWhileBusy = samples.toList().filter { it.ready && it.at > busyFrom && it.at < busyUntil }
        assertEquals(0, readyWhileBusy.size, "ready during the re-import\n" + timeline(edited, seen, readyWhileBusy))

        // As on first startup, Indexing begins before Importing ends, so those two leave no gap.
        assertTrue("begin Indexing" in texts.subList(importing, importEnd), "signals: $texts")

        // But before Importing begins, an Indexing run notices the change and ends: no progress is open for a moment.
        // Every gap in the re-import is shorter than the settle period.
        assertTrue(
            gapsWithNoProgressOpen(seen.subList(0, importing + 1)).isNotEmpty(),
            "no gap before Importing\n" + timeline(edited, seen, emptyList()),
        )
        val gaps = gapsWithNoProgressOpen(seen)
        assertTrue(
            gaps.all { it < ReadinessTracker.SETTLE },
            "gaps not all below ${ReadinessTracker.SETTLE}\n" + timeline(edited, seen, emptyList()),
        )

        val calculator = lsp.workspaceSymbols("Calculator").symbols.filter { it.name == "Calculator" }
        assertEquals(listOf(SymbolKind.CLASS), calculator.map { it.kind })
    }

    /** Whether [texts] show an Importing that began and a FINISHED after it. */
    private fun importFinished(texts: List<String>): Boolean =
        "import FINISHED" in texts.dropWhile { it != "begin Importing" }

    /** How long no progress was open each time all open progress ended and another began later in [signals]. */
    private fun gapsWithNoProgressOpen(signals: List<ReadinessSignal>): List<Duration> =
        gapsWithNoProgressOpenBetween(signals).map { it.first }

    /** Each gap of [gapsWithNoProgressOpen], with the `end` signal that opened it and the `begin` signal that closed it. */
    private fun gapsWithNoProgressOpenBetween(signals: List<ReadinessSignal>): List<Triple<Duration, ReadinessSignal, ReadinessSignal>> {
        var open = 0
        var quietSince: ReadinessSignal? = null
        val gaps = mutableListOf<Triple<Duration, ReadinessSignal, ReadinessSignal>>()
        for (signal in signals) {
            when {
                signal.text.startsWith("begin ") -> {
                    quietSince?.let { gaps += Triple(signal.at - it.at, it, signal) }
                    quietSince = null
                    open++
                }
                signal.text.startsWith("end ") -> if (--open == 0) quietSince = signal
            }
        }
        return gaps
    }

    /**
     * For a failure message: when each of [signals] came, each gap with no progress open and the signals around it, and
     * when each of the [readySamples] was asked and answered, all relative to [edited].
     */
    private fun timeline(edited: ComparableTimeMark, signals: List<ReadinessSignal>, readySamples: List<ReadinessSample>): String {
        fun ms(at: ComparableTimeMark) = (at - edited).inWholeMilliseconds.let { if (it < 0) "$it ms" else "+$it ms" }
        fun around(at: ComparableTimeMark): String {
            val before = signals.lastOrNull { it.at <= at }
            val after = signals.firstOrNull { it.at > at }
            return "after ${before?.let { "'${it.text}' by ${(at - it.at).inWholeMicroseconds} µs" } ?: "no signal"}, " +
                "before ${after?.let { "'${it.text}' by ${(it.at - at).inWholeMicroseconds} µs" } ?: "no signal"}"
        }
        return buildString {
            appendLine("signals:")
            signals.forEach { appendLine("  ${ms(it.at)} ${it.text}") }
            appendLine("gaps with no progress open (settle period ${ReadinessTracker.SETTLE}):")
            gapsWithNoProgressOpenBetween(signals).forEach { (gap, end, begin) ->
                appendLine("  $gap from ${ms(end.at)} '${end.text}' to ${ms(begin.at)} '${begin.text}'")
            }
            appendLine("ready samples:")
            readySamples.forEach {
                appendLine("  asked ${ms(it.at)} (${around(it.at)}), answered ${ms(it.answeredAt)} (${around(it.answeredAt)})")
            }
        }
    }
}
