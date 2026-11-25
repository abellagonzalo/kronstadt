@file:OptIn(ExperimentalPathApi::class) // copyToRecursively

package kronstadt.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.exists
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A Kronstadt server for contract tests: its own JVM on [projectRoot], with [home] as `user.home` (so its cache is the
 * test's), spoken to over stdio JSON-RPC as Claude Code speaks to it. Its stderr goes to [stderr].
 */
internal class KronstadtProcess(projectRoot: Path, home: Path, private val stderr: Path) {
    val process: Process = ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED",
        "-Duser.home=$home",
        "-D$LOG_INDEX_STATE=true",
        "-cp", System.getProperty("java.class.path"),
        "kronstadt.server.MainKt",
        "--project-root", projectRoot.toString(),
        "--readiness-timeout", "300",
    ).redirectError(stderr.toFile()).start()
    private val input = process.outputWriter()
    private val output = process.inputReader()
    private var nextId = 1

    /** The MCP handshake: `initialize`, then `notifications/initialized`. */
    fun initialize() {
        call("initialize", """{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"test","version":"0"}}""")
        notify("notifications/initialized")
    }

    /** Calls tool [name] with [arguments], a JSON object, and returns its result. */
    fun callTool(name: String, arguments: String): ToolResult {
        val response = call("tools/call", """{"name":"$name","arguments":$arguments}""")
        val result = requireNotNull(Json.parseToJsonElement(response).jsonObject["result"]) { "no result: $response" }.jsonObject
        return ToolResult(
            text = result.getValue("content").jsonArray.joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content },
            isError = result["isError"]?.jsonPrimitive?.booleanOrNull ?: false,
        )
    }

    /** Sends request [method] and returns the raw response line. */
    private fun call(method: String, params: String): String {
        val id = nextId++
        input.write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}${"\n"}""")
        input.flush()
        while (true) {
            val line = output.readLine() ?: error("Kronstadt closed stdout while answering $method; its stderr:\n${stderr()}")
            if (line.contains("\"id\":$id") && !line.contains("\"method\"")) return line
        }
    }

    private fun notify(method: String) {
        input.write("""{"jsonrpc":"2.0","method":"$method"}${"\n"}""")
        input.flush()
    }

    fun stderr(): String = if (stderr.exists()) Files.readString(stderr) else ""

    /**
     * Waits until kotlin-lsp's index is in the state Kronstadt's hand-off needs to refresh the Index Seed, as Kronstadt
     * logs it (see [LOG_INDEX_STATE]), and has stayed so for [hold]: kotlin-lsp leaves gaps of a few ms between progress
     * runs. Fails naming the last state, and so the progress still open, if that doesn't happen within [timeout].
     */
    fun awaitIndexComplete(timeout: Duration = 120.seconds, hold: Duration = 1.seconds) {
        val deadline = TimeSource.Monotonic.markNow() + timeout
        var logged = 0
        var since = TimeSource.Monotonic.markNow()
        while (true) {
            val states = indexStates()
            if (states.size != logged) {
                logged = states.size
                since = TimeSource.Monotonic.markNow()
            }
            if (states.lastOrNull() == "complete" && since.elapsedNow() >= hold) return
            if (deadline.hasPassedNow()) {
                fail("kotlin-lsp's index was not complete within $timeout; last index state: ${states.lastOrNull() ?: "none logged"}")
            }
            Thread.sleep(100)
        }
    }

    /** Each index state Kronstadt logged, oldest first. */
    private fun indexStates(): List<String> =
        stderr().lineSequence().filter { it.startsWith(INDEX_STATE_PREFIX) }.map { it.removePrefix(INDEX_STATE_PREFIX) }.toList()

    /** What Kronstadt's hand-off logged it did with the Index Seed as it ended, naming the open progress if it skipped the refresh for it. */
    fun handOffLog(): String =
        stderr().lineSequence().lastOrNull { it.startsWith("kronstadt: Index Seed not refreshed") || it.startsWith("kronstadt: refreshing the Index Seed") }
            ?: "no hand-off logged"

    /** SIGINT, SIGTERM 0.1 s later, SIGKILL 0.4 s after that, to Kronstadt's process only (measured in #52). */
    fun endLikeClaudeCode() {
        signal("INT")
        Thread.sleep(100)
        signal("TERM")
        Thread.sleep(400)
        process.destroyForcibly()
        assertTrue(process.waitFor(5, TimeUnit.SECONDS))
    }

    private fun signal(name: String) {
        check(ProcessBuilder("kill", "-$name", process.pid().toString()).start().waitFor() == 0) { "kill -$name failed" }
    }

    data class ToolResult(val text: String, val isError: Boolean)
}

/** The Index Seed in a seed slot under [seeds], the cache's `seed` directory, or null if there is none yet. */
internal fun seedIndex(seeds: Path): Path? =
    if (!seeds.exists()) null else Files.list(seeds).use { owners ->
        owners.map { it.resolve("kotlin-server") }.filter { it.exists() }.findFirst().orElse(null)
    }

/** A Git repo at [repo] holding the contract-test fixture in one commit. */
internal fun gitRepoFromFixture(repo: Path): Path {
    val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :server:contractTest" })
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
