package kronstadt.server

import kronstadt.workspace.SeedRefreshStart
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail

class HandOffTest {
    private val log = Path.of("/tmp/seed-refresh.log")

    @Test
    fun `refreshes the Index Seed when the index is complete and kotlin-lsp answered shutdown`() {
        // indexComplete ignores the readiness settle period; ReadinessTrackerTest pins that.
        val message = handOffMessage(indexComplete = true, answeredShutdown = true) { SeedRefreshStart.Started(log) }
        assertEquals("refreshing the Index Seed once kotlin-lsp exits, in the background; see $log", message)
    }

    @Test
    fun `skips the refresh while kotlin-lsp still has progress open`() {
        val message = handOffMessage(indexComplete = false, answeredShutdown = true) { fail("refresh started") }
        assertEquals("Index Seed not refreshed: kotlin-lsp had not finished indexing", message)
    }

    @Test
    fun `skips the refresh when kotlin-lsp did not answer shutdown`() {
        val message = handOffMessage(indexComplete = true, answeredShutdown = false) { fail("refresh started") }
        assertEquals("Index Seed not refreshed: kotlin-lsp did not answer shutdown", message)
    }

    @Test
    fun `reports why the refresh itself was skipped`() {
        val message = handOffMessage(indexComplete = true, answeredShutdown = true) { SeedRefreshStart.Skipped("no seed slot") }
        assertEquals("Index Seed not refreshed: no seed slot", message)
    }
}
