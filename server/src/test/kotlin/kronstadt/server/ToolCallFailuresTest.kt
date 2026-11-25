package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.Readiness
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/** What the agent sees when a tool's work throws something no use case expects. */
class ToolCallFailuresTest {
    private val root = Files.createTempDirectory("kronstadt-tool-failures").toRealPath()

    @AfterTest
    fun cleanup() {
        root.toFile().deleteRecursively()
    }

    @Test
    fun `a class that fails to load says the install changed and to reconnect with mcp`() = runTest {
        val (failed, text) = callThrowing(NoClassDefFoundError("a/b/C"))

        assertEquals(true, failed)
        assertEquals(
            "Kronstadt's installed files changed while it was running (NoClassDefFoundError: a/b/C); reconnect it with /mcp.",
            text,
        )
    }

    @Test
    fun `any other linkage error is reported the same way`() = runTest {
        val (failed, text) = callThrowing(IncompatibleClassChangeError("a.b.C"))

        assertEquals(true, failed)
        assertEquals(
            "Kronstadt's installed files changed while it was running (IncompatibleClassChangeError: a.b.C); reconnect it with /mcp.",
            text,
        )
    }

    @Test
    fun `an error that isn't the JVM's own is named like an unexpected exception`() = runTest {
        val (failed, text) = callThrowing(NotImplementedError("later"))

        assertEquals(true, failed)
        assertEquals("NotImplementedError: later", text)
    }

    @Test
    fun `a virtual machine error is not turned into a tool result`() = runTest {
        assertFailsWith<OutOfMemoryError> { callThrowing(OutOfMemoryError("heap")) }
    }

    /** Calls kt_find_symbol on a server whose language server throws [failure] when asked whether it is ready. */
    private suspend fun callThrowing(failure: Throwable): Pair<Boolean, String> {
        val intelligence = object : UnstubbedCodeIntelligence() {
            override suspend fun awaitReady(timeout: Duration): Readiness = throw failure
        }
        return ToolHarness(root, intelligence).call("kt_find_symbol", """{"query": "greet"}""")
    }
}
