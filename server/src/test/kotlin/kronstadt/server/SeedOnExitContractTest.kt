@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.server

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Seam 2 acceptance test for #52: Kronstadt, ended the way Claude Code ends an MCP server once kotlin-lsp is ready,
 * still refreshes its repo's Index Seed.
 */
@Tag("contract")
class SeedOnExitContractTest {
    private val tmp: Path = Files.createTempDirectory("kronstadt-seed-on-exit").toRealPath()
    private val home = tmp.resolve("home")
    private val leftovers = mutableListOf<ProcessHandle>()

    @AfterTest
    fun cleanup() {
        leftovers.forEach { it.destroyForcibly() }
        tmp.deleteRecursively()
    }

    @Test
    fun `ended like Claude Code ends it, Kronstadt still refreshes the Index Seed`() {
        val repo = gitRepoFromFixture(tmp.resolve("repo"))
        val kronstadt = KronstadtProcess(repo, home, tmp.resolve("kronstadt.stderr"))
        leftovers += kronstadt.process.toHandle()

        kronstadt.initialize()
        val found = kronstadt.callTool("kt_find_symbol", """{"query":"Shape"}""")
        assertContains(found.text, "Shape", message = "kotlin-lsp should be ready: $found")
        leftovers += kronstadt.process.toHandle().descendants().toList()

        kronstadt.awaitIndexComplete()
        kronstadt.endLikeClaudeCode()

        val slot = home.resolve("Library/Caches/kronstadt/seed")
        val deadline = TimeSource.Monotonic.markNow() + 60.seconds
        while (seedIndex(slot) == null && deadline.hasNotPassedNow()) Thread.sleep(200)
        val seed = assertNotNull(seedIndex(slot), "no Index Seed in $slot; ${kronstadt.handOffLog()}; Kronstadt's stderr:\n${kronstadt.stderr()}")
        assertContains(seed.resolveSibling("last-refresh.log").readText(), "kronstadt: refreshed the Index Seed")
    }
}
