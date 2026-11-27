package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.Readiness
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
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

    @Test
    fun `a tool's first call after its install was rebuilt says to reconnect with mcp, whichever class fails to load`() = runTest {
        for (tool in ToolHarness(root, UnstubbedCodeIntelligence()).toolNames) {
            val install = RebuildableInstall(javaClass.classLoader)
            val harness = ToolHarness(root, UnstubbedCodeIntelligence(), install = install)
            install.rebuild()

            val (failed, text) = harness.call(tool, "{}")

            assertEquals(true, failed, tool)
            assertTrue(
                text.startsWith("Kronstadt's installed files changed while it was running (NoClassDefFoundError: kronstadt/server/") &&
                    text.endsWith("); reconnect it with /mcp."),
                "$tool: $text",
            )
        }
    }

    /** Calls kt_find_symbol on a server whose language server throws [failure] when asked whether it is ready. */
    private suspend fun callThrowing(failure: Throwable): Pair<Boolean, String> {
        val intelligence = object : UnstubbedCodeIntelligence() {
            override suspend fun awaitReady(timeout: Duration): Readiness = throw failure
        }
        return ToolHarness(root, intelligence).call("kt_find_symbol", """{"query": "greet"}""")
    }
}

/**
 * The server's own classes, loaded from its build output by this loader rather than the test's, as from an install. Once
 * [rebuild] is called, a class not loaded yet fails to load, as one does in a JVM whose install was rebuilt under it.
 */
private class RebuildableInstall(parent: ClassLoader) : ClassLoader(parent) {
    private val classes = Path.of(KronstadtTools::class.java.protectionDomain.codeSource.location.toURI())

    @Volatile
    private var rebuilt = false

    fun rebuild() {
        rebuilt = true
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        findLoadedClass(name) ?: run {
            val file = classes.resolve(name.replace('.', '/') + ".class")
            if (!Files.isRegularFile(file)) return super.loadClass(name, resolve)
            if (rebuilt) throw NoClassDefFoundError(name.replace('.', '/'))
            val bytes = Files.readAllBytes(file)
            defineClass(name, bytes, 0, bytes.size)
        }
    }
}
