@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.appendText
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
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
    private lateinit var project: Path
    private lateinit var indexDir: Path
    private lateinit var lsp: LspCodeIntelligence

    @BeforeAll
    fun start() {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :lsp:contractTest" })
        project = Files.createTempDirectory("kronstadt-fixture").toRealPath()
        fixture.copyToRecursively(project, followLinks = false, overwrite = true)
        val command = (System.getenv("KRONSTADT_LANGUAGE_SERVER") ?: "kotlin-lsp --stdio").trim().split(Regex("\\s+"))
        indexDir = Files.createTempDirectory("kronstadt-index").toRealPath().resolve("kotlin-server")
        lsp = runBlocking { LspCodeIntelligence.start(command, project, indexDir) }
    }

    @AfterAll
    fun stop() {
        if (::lsp.isInitialized) lsp.close()
        if (::project.isInitialized) project.deleteRecursively()
        if (::indexDir.isInitialized) indexDir.parent.deleteRecursively()
    }

    /** Whether Kronstadt reported ready at [at]. */
    private class ReadinessSample(val at: ComparableTimeMark, val ready: Boolean)

    @Test
    fun `a build script change re-imports with no import phase but FINISHED, through gaps the settle period covers`() = runBlocking {
        assertEquals(Readiness.Ready, lsp.awaitReady(5.minutes))

        val signals = Collections.synchronizedList(mutableListOf<ReadinessSignal>())
        val samples = Collections.synchronizedList(mutableListOf<ReadinessSample>())
        val recorder = launch(Dispatchers.Default) { lsp.readinessSignals.collect { signals += it } }
        val sampler = launch(Dispatchers.Default) {
            while (true) {
                samples += ReadinessSample(TimeSource.Monotonic.markNow(), lsp.awaitReady(Duration.ZERO) == Readiness.Ready)
                delay(5.milliseconds)
            }
        }
        delay(100.milliseconds) // the recorder is subscribed before the change

        val edited = TimeSource.Monotonic.markNow()
        project.resolve("build.gradle.kts").appendText(
            "\ndependencies {\n    implementation(\"org.jetbrains:annotations:24.1.0\")\n}\n",
        )
        withTimeout(5.minutes) {
            while (!importFinished(signals.toList().map { it.text })) delay(50.milliseconds)
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
        assertTrue(importing >= 0 && importEnd > importing, "signals: $texts")

        // The import state never leaves FINISHED: open progress is the only sign of a re-import.
        assertEquals(listOf("import FINISHED"), texts.filter { it.startsWith("import ") }, "signals: $texts")
        assertTrue(texts.indexOf("import FINISHED") > importing, "signals: $texts")

        // Not ready at any point from the first sign of the re-import until its last progress ended.
        val busyFrom = seen.first().at
        val busyUntil = seen.last().at
        val readyWhileBusy = samples.toList().filter { it.ready && it.at > busyFrom && it.at < busyUntil }
        assertEquals(0, readyWhileBusy.size, "ready during the re-import; signals: $texts")

        // As on first startup, Indexing begins before Importing ends, so those two leave no gap.
        assertTrue("begin Indexing" in texts.subList(importing, importEnd), "signals: $texts")

        // But before Importing begins, an Indexing run notices the change and ends: no progress is open for a moment.
        // Every gap in the re-import is shorter than the settle period.
        assertTrue(gapsWithNoProgressOpen(seen.subList(0, importing + 1)).isNotEmpty(), "no gap before Importing; signals: $texts")
        val gaps = gapsWithNoProgressOpen(seen)
        assertTrue(gaps.all { it < ReadinessTracker.SETTLE }, "gaps $gaps not all below ${ReadinessTracker.SETTLE}")

        val calculator = lsp.workspaceSymbols("Calculator").symbols.filter { it.name == "Calculator" }
        assertEquals(listOf(SymbolKind.CLASS), calculator.map { it.kind })
    }

    /** Whether [texts] show an Importing that began and a FINISHED after it. */
    private fun importFinished(texts: List<String>): Boolean =
        "import FINISHED" in texts.dropWhile { it != "begin Importing" }

    /** How long no progress was open each time all open progress ended and another began later in [signals]. */
    private fun gapsWithNoProgressOpen(signals: List<ReadinessSignal>): List<Duration> {
        var open = 0
        var quietSince: ComparableTimeMark? = null
        val gaps = mutableListOf<Duration>()
        for (signal in signals) {
            when {
                signal.text.startsWith("begin ") -> {
                    quietSince?.let { gaps += signal.at - it }
                    quietSince = null
                    open++
                }
                signal.text.startsWith("end ") -> if (--open == 0) quietSince = signal.at
            }
        }
        return gaps
    }
}
