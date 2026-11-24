package kronstadt.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kronstadt.core.port.CodeIntelligence
import kronstadt.workspace.IndexClaim
import kronstadt.workspace.IndexDirectories
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** Startup when another Kronstadt already serves the worktree, through to the error a tool call returns. */
class StartupTest {
    private val tmp: Path = Files.createTempDirectory("kronstadt-startup").toRealPath()
    private val worktree = tmp.resolve("worktree").createDirectories().apply {
        resolve("A.kt").writeText("package a\n\nfun greet(): Int = 1\n")
    }
    private val steps = mutableListOf<String>()

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `when another instance holds the index, nothing is pruned, seeded or started, and analysis fails naming it`() = runTest {
        val startup = runCatching { startUpRecording(IndexClaim.HeldByAnother(4242)) }

        assertEquals(emptyList(), steps)
        val (failed, text) = callTool(startup, "kt_find_symbol", """{"query": "greet"}""")
        assertEquals(true, failed)
        assertEquals(
            "Another Kronstadt (pid 4242) is already serving $worktree, and only one instance " +
                "per worktree can use its index. Close that session and then reconnect Kronstadt (`/mcp` in Claude " +
                "Code), or start this session in a separate Git worktree.",
            text,
        )
    }

    @Test
    fun `the message still works when the other instance's pid can't be read`() {
        val failure = assertIs<AgentFacingFailure>(runCatching { startUpRecording(IndexClaim.HeldByAnother(null)) }.exceptionOrNull())

        assertEquals(
            "Another Kronstadt is already serving $worktree, and only one instance per worktree can use its index. " +
                "Close that session and then reconnect Kronstadt (`/mcp` in Claude Code), or start this session in a " +
                "separate Git worktree.",
            failure.message,
        )
    }

    @Test
    fun `an unexpected startup failure reaches the agent with its exception's class name`() = runTest {
        val (failed, text) = callTool(Result.failure<Nothing>(IllegalStateException("no kotlin-lsp")), "kt_find_symbol", """{"query": "greet"}""")

        assertEquals(true, failed)
        assertEquals("IllegalStateException: no kotlin-lsp", text)
    }

    @Test
    fun `the instance that holds the claim prunes, prepares the index and starts the language server on it`() {
        val claim = IndexDirectories(tmp.resolve("cache")).claim(worktree)
        try {
            assertEquals("started on index", startUpRecording(claim))
            assertEquals(listOf("prune", "prepare", "start"), steps)
        } finally {
            (claim as IndexClaim.Held).release()
        }
    }

    @Test
    fun `when the lock can't be taken for another reason, the instance starts as it would without one`() {
        assertEquals("started on index", startUpRecording(IndexClaim.Unavailable("locking not supported")))
        assertEquals(listOf("prune", "prepare", "start"), steps)
    }

    private fun startUpRecording(claim: IndexClaim): String = startUp(
        worktree,
        claim = { claim },
        prune = { steps += "prune" },
        prepare = { steps += "prepare"; "index" },
        start = { index -> steps += "start"; "started on $index" },
    )

    /** Calls [tool] on a server whose language server startup ended with [startup]; whether it failed, and its text. */
    private suspend fun callTool(startup: Result<*>, tool: String, arguments: String): Pair<Boolean, String> {
        @Suppress("UNCHECKED_CAST")
        val intelligence = DeferredCodeIntelligence(CompletableDeferred(startup as Result<CodeIntelligence>))
        return ToolHarness(worktree, intelligence, readinessTimeout = 1.seconds).call(tool, arguments)
    }
}
