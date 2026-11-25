@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.server

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Seam 2 acceptance test for #97: a second Kronstadt on a worktree another one serves fails analysis with
 * [worktreeInUse]'s message, starts no kotlin-lsp and changes neither the index nor the Index Seed, while the first
 * keeps answering and, ended like Claude Code ends it, still refreshes the seed.
 */
@Tag("contract")
class TwoServersContractTest {
    private val tmp: Path = Files.createTempDirectory("kronstadt-two-servers").toRealPath()
    private val home = tmp.resolve("home")
    private val cache = home.resolve("Library/Caches/kronstadt")
    private val tracked = mutableListOf<ProcessHandle>()

    @AfterTest
    fun cleanup() {
        // kotlin-lsp and the detached seed refresh outlive a killed Kronstadt, so they are tracked or found by path.
        (tracked + tracked.flatMap { it.descendants().toList() } + startedHere()).forEach { it.destroyForcibly() }
        tmp.deleteRecursively()
    }

    @Test
    @Timeout(10, unit = TimeUnit.MINUTES)
    fun `a second Kronstadt on the worktree fails clearly and changes nothing, and the first keeps working`() {
        val repo = gitRepoFromFixture(tmp.resolve("repo"))
        val a = start("a", repo)
        a.initialize()
        assertFindsShape(a)
        val aLanguageServer = a.process.toHandle().descendants().toList()
        tracked += aLanguageServer
        assertTrue(aLanguageServer.isNotEmpty(), "A started no kotlin-lsp")

        val index = Files.list(cache.resolve("index")).use { it.toList() }.single().resolve("kotlin-server")
        // A's kotlin-lsp writes its index now and then even when idle: frozen, it can't, so only B could change it.
        signal(aLanguageServer, "STOP")
        val b = try {
            val indexBefore = snapshot(index)
            val b = start("b", repo)
            b.initialize()
            val refused = b.callTool("kt_find_symbol", FIND_SHAPE)
            tracked += b.process.toHandle().descendants().toList()
            assertTrue(refused.isError, "B should fail: $refused")
            assertEquals(worktreeInUse(a.process.pid(), repo), refused.text)
            for (lockText in listOf("LOCK", "RocksDB")) assertFalse(refused.text.contains(lockText, ignoreCase = true))
            assertEquals(emptyList(), b.process.toHandle().descendants().toList(), "B started a child process")
            assertEquals(indexBefore, snapshot(index), "B changed the worktree's index")
            b
        } finally {
            signal(aLanguageServer, "CONT")
        }
        assertEquals(a.process.pid().toString(), index.resolveSibling("lock").readText().trim(), "B took over the lock")
        assertNull(seedIndex(cache.resolve("seed")), "an Index Seed appeared before A ended")

        assertFindsShape(a)

        assertTrue(b.process.isAlive, "B should be alive while A ends")
        // Resumed, A's kotlin-lsp may re-index; ended before that's done, A would rightly skip the refresh.
        a.awaitIndexComplete()
        a.endLikeClaudeCode()
        val seed = awaitNotNull(60.seconds) { seedIndex(cache.resolve("seed")) }
            ?: error("A refreshed no Index Seed; ${a.handOff()}; A's stderr:\n${a.stderr()}")
        val log = seed.resolveSibling("last-refresh.log")
        assertContains(log.readText(), "kronstadt: refreshed the Index Seed")
        val seedAfterA = snapshot(seed)
        val logAfterA = log.readText()

        b.endLikeClaudeCode()
        // A refresh B started would have to wait for a kotlin-lsp that doesn't exist, then clone: give it time to show.
        Thread.sleep(3_000)
        assertEquals(logAfterA, log.readText(), "B refreshed the Index Seed; B's stderr:\n${b.stderr()}")
        assertEquals(seedAfterA, snapshot(seed), "B changed the Index Seed")
        // A's kotlin-lsp wrote the index until it exited, so B's shutdown is checked by what only B could leave.
        assertEquals(a.process.pid().toString(), index.resolveSibling("lock").readText().trim(), "B wrote the lock")
        val nextToIndex = Files.list(index.parent).use { it.map { path -> path.fileName.toString() }.toList() }.sorted()
        assertEquals(listOf("kotlin-server", "lock", "worktree"), nextToIndex, "B left something next to the index")

        val deadline = TimeSource.Monotonic.markNow() + 30.seconds
        while (leftBehind().isNotEmpty() && deadline.hasNotPassedNow()) Thread.sleep(200)
        assertEquals(emptyList(), leftBehind().map { it.info().commandLine().orElse(it.toString()) }, "processes left behind")
    }

    private fun start(name: String, repo: Path): KronstadtProcess =
        KronstadtProcess(repo, home, tmp.resolve("$name.stderr")).also { tracked += it.process.toHandle() }

    private fun assertFindsShape(kronstadt: KronstadtProcess) {
        val found = kronstadt.callTool("kt_find_symbol", FIND_SHAPE)
        assertFalse(found.isError, "kt_find_symbol failed: $found")
        assertContains(found.text, "Shape")
    }

    private fun leftBehind(): List<ProcessHandle> = (tracked + startedHere()).filter { it.isAlive }

    /** Processes whose command line names this test's directory, such as a detached seed refresh. */
    private fun startedHere(): List<ProcessHandle> =
        ProcessHandle.allProcesses().filter { process ->
            process.info().commandLine().filter { tmp.toString() in it }.isPresent
        }.toList()

    /** Each file under [dir] with its size and modification time. */
    private fun snapshot(dir: Path): Map<Path, Pair<Long, FileTime>> = Files.walk(dir).use { paths ->
        paths.filter { it.isRegularFile() }.toList().associate { dir.relativize(it) to (Files.size(it) to Files.getLastModifiedTime(it)) }
    }

    private fun signal(processes: List<ProcessHandle>, name: String) {
        val kill = ProcessBuilder(listOf("kill", "-$name") + processes.map { it.pid().toString() }).start()
        check(kill.waitFor() == 0) { "kill -$name failed" }
    }

    private fun <T : Any> awaitNotNull(timeout: Duration, probe: () -> T?): T? {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        while (true) {
            probe()?.let { return it }
            if (deadline.hasPassedNow()) return null
            Thread.sleep(200)
        }
    }

    private companion object {
        const val FIND_SHAPE = """{"query":"Shape"}"""
    }
}
