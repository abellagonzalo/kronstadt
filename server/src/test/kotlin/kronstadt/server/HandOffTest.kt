package kronstadt.server

import kronstadt.lsp.IndexState
import kronstadt.workspace.SeedRefreshStart
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class HandOffTest {
    private val log = Path.of("/tmp/seed-refresh.log")
    private val complete = IndexState(complete = true, importPhase = "FINISHED", openProgress = emptyMap())

    @Test
    fun `refreshes the Index Seed when the index is complete and kotlin-lsp answered shutdown`() {
        // indexComplete ignores the readiness settle period; ReadinessTrackerTest pins that.
        val message = handOffMessage(complete, answeredShutdown = true) { SeedRefreshStart.Started(log) }
        assertEquals("refreshing the Index Seed once kotlin-lsp exits, in the background; see $log", message)
    }

    @Test
    fun `skips the refresh while kotlin-lsp still has progress open, and names that progress`() {
        val indexing = IndexState(complete = false, importPhase = "FINISHED", openProgress = mapOf("t1" to "Indexing"))
        val message = handOffMessage(indexing, answeredShutdown = true) { fail("refresh started") }
        assertEquals(
            "Index Seed not refreshed: kotlin-lsp had not finished indexing (import FINISHED; open progress: Indexing [t1])",
            message,
        )
    }

    @Test
    fun `skips the refresh when kotlin-lsp did not answer shutdown`() {
        val message = handOffMessage(complete, answeredShutdown = false) { fail("refresh started") }
        assertEquals("Index Seed not refreshed: kotlin-lsp did not answer shutdown", message)
    }

    @Test
    fun `reports why the refresh itself was skipped`() {
        val message = handOffMessage(complete, answeredShutdown = true) { SeedRefreshStart.Skipped("no seed slot") }
        assertEquals("Index Seed not refreshed: no seed slot", message)
    }
}
