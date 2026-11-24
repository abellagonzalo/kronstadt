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

/**
 * A Kronstadt server for contract tests: its own JVM on [projectRoot], with [home] as `user.home` (so its cache is the
 * test's), spoken to over stdio JSON-RPC as Claude Code speaks to it. Its stderr goes to [stderr].
 */
internal class KronstadtProcess(projectRoot: Path, home: Path, private val stderr: Path) {
    val process: Process = ProcessBuilder(
        ProcessHandle.current().info().command().orElseThrow(),
        "--enable-native-access=ALL-UNNAMED",
        "-Duser.home=$home",
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
    fun call(method: String, params: String): String {
        val id = nextId++
        input.write("""{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}${"\n"}""")
        input.flush()
        while (true) {
            val line = output.readLine() ?: error("Kronstadt closed stdout while answering $method; its stderr:\n${stderr()}")
            if (line.contains("\"id\":$id") && !line.contains("\"method\"")) return line
        }
    }

    fun notify(method: String) {
        input.write("""{"jsonrpc":"2.0","method":"$method"}${"\n"}""")
        input.flush()
    }

    fun stderr(): String = if (stderr.exists()) Files.readString(stderr) else ""

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
