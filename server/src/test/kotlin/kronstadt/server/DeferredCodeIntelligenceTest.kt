@file:OptIn(ExperimentalCoroutinesApi::class) // currentTime, runCurrent

package kronstadt.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Waiting for the index, and project-wide queries, while the language server may still be starting. Time is virtual. */
class DeferredCodeIntelligenceTest {
    @Test
    fun `awaitReady gives up when startup doesn't finish in time, reporting the Starting phase`() = runTest {
        val intelligence = DeferredCodeIntelligence(CompletableDeferred(), testTimeSource)

        assertEquals(Readiness.NotReady("Starting", 10.seconds), intelligence.awaitReady(10.seconds))
        assertEquals(10.seconds, currentTime.milliseconds)
    }

    @Test
    fun `awaitReady gives the started language server only the time startup left, and reports the whole wait`() = runTest {
        val startup = CompletableDeferred<Result<CodeIntelligence>>()
        val started = StartedIntelligence(indexingFor = 5.seconds, readiness = Readiness.NotReady("Indexing", 5.seconds))
        launch {
            delay(3.seconds)
            startup.complete(Result.success(started))
        }

        val readiness = DeferredCodeIntelligence(startup, testTimeSource).awaitReady(10.seconds)

        assertEquals(listOf(7.seconds), started.timeouts)
        assertEquals(Readiness.NotReady("Indexing", 8.seconds), readiness)
    }

    @Test
    fun `awaitReady is Ready when startup has finished and the language server is ready`() = runTest {
        val intelligence = DeferredCodeIntelligence(CompletableDeferred(Result.success(StartedIntelligence())), testTimeSource)

        assertEquals(Readiness.Ready, intelligence.awaitReady(10.seconds))
    }

    @Test
    fun `a query after a failed startup throws the startup error`() = runTest {
        val failed = DeferredCodeIntelligence(CompletableDeferred(Result.failure(IllegalStateException("no kotlin-lsp"))))

        val error = assertFailsWith<IllegalStateException> { failed.workspaceSymbols("Order") }
        assertEquals("no kotlin-lsp", error.message)
    }

    @Test
    fun `a query during startup waits for it, then asks the started language server`() = runTest {
        val startup = CompletableDeferred<Result<CodeIntelligence>>()
        val started = StartedIntelligence()
        val call = async { DeferredCodeIntelligence(startup).workspaceSymbols("Order") }
        runCurrent()
        assertFalse(call.isCompleted)

        startup.complete(Result.success(started))
        call.await()

        assertEquals(listOf("Order"), started.queries)
    }
}

/** A started language server that takes [indexingFor] to answer [awaitReady] with [readiness], recording what it is asked. */
private class StartedIntelligence(
    private val indexingFor: Duration = Duration.ZERO,
    private val readiness: Readiness = Readiness.Ready,
) : CodeIntelligence {
    val timeouts = mutableListOf<Duration>()
    val queries = mutableListOf<String>()

    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = error("not analysed in these tests")

    override suspend fun workspaceSymbols(query: String): SymbolSearch = SymbolSearch(emptyList()).also { queries += query }

    override suspend fun awaitReady(timeout: Duration): Readiness {
        timeouts += timeout
        delay(indexingFor)
        return readiness
    }
}
