@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.server

import org.junit.jupiter.api.Tag
import java.io.BufferedReader
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
        val repo = gitRepoFromFixture()
        val kronstadt = ProcessBuilder(
            ProcessHandle.current().info().command().orElseThrow(),
            "--enable-native-access=ALL-UNNAMED",
            "-Duser.home=$home",
            "-cp", System.getProperty("java.class.path"),
            "kronstadt.server.MainKt",
            "--project-root", repo.toString(),
            "--readiness-timeout", "300",
        ).redirectError(tmp.resolve("kronstadt.stderr").toFile()).start()
        leftovers += kronstadt.toHandle()
        val input = kronstadt.outputWriter()
        val output = kronstadt.inputReader()

        call(input, output, 1, "initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}""")
        notify(input, "notifications/initialized")
        val found = call(input, output, 2, "tools/call", """{"name":"kt_find_symbol","arguments":{"query":"Shape"}}""")
        assertContains(found, "Shape", message = "kotlin-lsp should be ready: $found")
        leftovers += kronstadt.toHandle().descendants().toList()

        endLikeClaudeCode(kronstadt)

        val slot = home.resolve("Library/Caches/kronstadt/seed")
        val deadline = TimeSource.Monotonic.markNow() + 60.seconds
        while (seedIndex(slot) == null && deadline.hasNotPassedNow()) Thread.sleep(200)
        val stderr = tmp.resolve("kronstadt.stderr").readText()
        val seed = assertNotNull(seedIndex(slot), "no Index Seed in $slot; Kronstadt's stderr:\n$stderr")
        assertContains(seed.resolveSibling("last-refresh.log").readText(), "kronstadt: refreshed the Index Seed")
    }

    /** SIGINT, SIGTERM 0.1 s later, SIGKILL 0.4 s after that, to Kronstadt's process only (measured in #52). */
    private fun endLikeClaudeCode(process: Process) {
        signal(process, "INT")
        Thread.sleep(100)
        signal(process, "TERM")
        Thread.sleep(400)
        process.destroyForcibly()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
    }

    private fun seedIndex(seeds: Path): Path? =
        if (!seeds.exists()) null else Files.list(seeds).use { owners ->
            owners.map { it.resolve("kotlin-server") }.filter { it.exists() }.findFirst().orElse(null)
        }

    private fun signal(process: Process, name: String) {
        check(ProcessBuilder("kill", "-$name", process.pid().toString()).start().waitFor() == 0) { "kill -$name failed" }
    }

    private fun call(input: Writer, output: BufferedReader, id: Int, method: String, params: String): String {
        input.write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""" + "\n")
        input.flush()
        while (true) {
            val line = output.readLine() ?: error("Kronstadt closed stdout while answering $method")
            if (line.contains("\"id\":$id") && !line.contains("\"method\"")) return line
        }
    }

    private fun notify(input: Writer, method: String) {
        input.write("""{"jsonrpc":"2.0","method":"$method"}""" + "\n")
        input.flush()
    }

    private fun gitRepoFromFixture(): Path {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :server:contractTest" })
        val repo = tmp.resolve("repo")
        fixture.copyToRecursively(repo, followLinks = false, overwrite = false)
        git(repo, "init", "--quiet")
        git(repo, "add", ".")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "fixture")
        return repo
    }

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).inheritIO().start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed" }
    }
}
