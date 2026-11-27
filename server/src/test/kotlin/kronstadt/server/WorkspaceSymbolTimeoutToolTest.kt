package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolSearch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** What the tools answer when the index doesn't answer a name search in time (#211). */
class WorkspaceSymbolTimeoutToolTest {
    private val root: Path = Files.createTempDirectory("workspace-symbol-timeout").toRealPath()

    init {
        root.resolve("A.kt").writeText("package a\n\nclass Shape {\n    override fun toString() = \"\"\n}\n")
    }

    private val tools = ToolHarness(root, TimingOutIntelligence())

    @Test
    fun `a lookup without a file that times out asks for the file, never says not found`() = runTest {
        assertEquals(
            true to "Looking up 'Shape/toString' without a file timed out: 'toString' is too common a name for kotlin-lsp " +
                "to search in time; pass its file",
            tools.call("kt_read_symbol", """{"symbol": "Shape/toString"}"""),
        )
    }

    @Test
    fun `a find that times out asks for a narrower query, never says no matches`() = runTest {
        assertEquals(
            true to "Searching for 'toString' timed out: it is too common a name for kotlin-lsp to search in time; " +
                "narrow the query with a longer, more specific name",
            tools.call("kt_find_symbol", """{"query": "toString"}"""),
        )
    }
}

/** Always ready; every workspace symbol search times out. */
private class TimingOutIntelligence : UnstubbedCodeIntelligence() {
    override suspend fun workspaceSymbols(query: String): SymbolSearch =
        SymbolSearch(emptyList(), SymbolSearch.Completeness.TIMED_OUT)

    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
