@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.benchmark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kronstadt.workspace.DetachedSeedRefresh
import kronstadt.workspace.IndexDirectories
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.readLines
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * The target project's Index Seed, frozen for the whole Benchmark.
 *
 * Kronstadt can't be told from outside not to refresh the seed when a session ends (ADR 0005), so the seed is frozen
 * around it instead: after warming, a copy-on-write copy is saved to [frozen]; after each session with Kronstadt, the
 * refresh it triggered is awaited through `last-refresh.log`; before each session with Kronstadt, the saved copy is put
 * back, discarding that refresh.
 */
internal class FrozenSeed(
    private val kronstadt: Path,
    private val gitCommonDir: Path,
    private val frozen: Path,
    private val warmQuery: String,
    private val logs: Path,
    private val directories: IndexDirectories = IndexDirectories.default(),
    private val readinessTimeout: Duration = 30.minutes,
    private val refreshStart: Duration = 15.seconds,
    private val refreshTimeout: Duration = 2.minutes,
) : IndexSeed {
    private val slot: Path by lazy { directories.seedSlot(gitCommonDir) }
    private val refreshLog: Path get() = slot.resolveSibling(DetachedSeedRefresh.LOG_FILE)

    /**
     * Deletes the seed so [worktree] indexes cold, has Kronstadt index it, and returns how long that took until a tool
     * that needs the index answered. Then saves the seed Kronstadt's exit refreshed.
     */
    override fun warm(worktree: Path): Duration {
        slot.deleteRecursively()
        val started = Instant.now()
        val coldStart = serve(worktree, logs.resolve("warm-seed.kronstadt.log"))
        val refresh = awaitRefresh(started, required = true)
        check(refresh == REFRESHED) { "Kronstadt didn't seed the index it warmed: $refresh (see $refreshLog)" }
        frozen.deleteRecursively()
        cloneTree(slot, frozen)
        return coldStart
    }

    override fun restore() {
        check(frozen.exists()) { "no frozen Index Seed at $frozen: warm it first" }
        slot.deleteRecursively()
        cloneTree(frozen, slot)
    }

    override fun awaitRefresh(since: Instant) {
        awaitRefresh(since, required = false)
    }

    /** Whether the seed holds the same files, by path and size, as the frozen copy. */
    fun isFrozen(): Boolean = slot.exists() && listing(slot) == listing(frozen)

    /**
     * Has Kronstadt serve [worktree] until a tool that needs the index answers, the way a session with Kronstadt starts,
     * logging Kronstadt's stderr to [stderr], and returns how long that took.
     */
    fun serve(worktree: Path, stderr: Path): Duration =
        McpClient.start(kronstadt, worktree, readinessTimeout, stderr).use { client ->
            val mark = TimeSource.Monotonic.markNow()
            client.callTool("kt_find_symbol", buildJsonObject { put("query", warmQuery) })
            mark.elapsedNow()
        }

    /**
     * Waits until the refresh that a Kronstadt ending after [since] started has logged its outcome, and returns that
     * line. A refresh logs to a new `last-refresh.log` as soon as it starts; if none does within [refreshStart] (the
     * index wasn't complete, so there was nothing to seed), returns null, or fails if [required].
     */
    private fun awaitRefresh(since: Instant, required: Boolean): String? {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            val outcome = outcomeSince(since)
            when {
                outcome != null && outcome != IN_PROGRESS -> return outcome
                outcome == null && started.elapsedNow() > refreshStart ->
                    if (required) error("Kronstadt started no Index Seed refresh within $refreshStart") else return null
                started.elapsedNow() > refreshStart + refreshTimeout ->
                    error("the Index Seed refresh didn't finish within $refreshTimeout; see $refreshLog")
            }
            Thread.sleep(250)
        }
    }

    /** The outcome logged by a refresh started after [since]: [REFRESHED], the reason it skipped, [IN_PROGRESS], or null if none started. */
    private fun outcomeSince(since: Instant): String? {
        if (!refreshLog.exists() || refreshLog.getLastModifiedTime().toInstant() < since) return null
        val last = refreshLog.readLines().lastOrNull { it.isNotBlank() } ?: return IN_PROGRESS
        return when {
            "refreshed the Index Seed" in last -> REFRESHED
            "Index Seed not refreshed" in last -> last.substringAfter("kronstadt: ")
            else -> IN_PROGRESS
        }
    }

    private companion object {
        const val REFRESHED = "refreshed"
        const val IN_PROGRESS = "in progress"

        /**
         * Copies a directory tree copy-on-write, as Kronstadt clones the seed: macOS's own `cp -c` uses clonefile(2). Named
         * by path, since GNU coreutils' `cp` is often first on the PATH and has no `-c`.
         */
        fun cloneTree(source: Path, target: Path) {
            Files.createDirectories(target.parent)
            Processes.output("/bin/cp", "-c", "-R", source.toString(), target.toString())
        }

        fun listing(root: Path): Map<String, Long> = Files.walk(root).use { paths ->
            paths.filter { Files.isRegularFile(it) }.toList().associate { root.relativize(it).toString() to Files.size(it) }
        }
    }
}

/** Just enough of an MCP client over stdio to call one of Kronstadt's tools. */
internal class McpClient private constructor(private val process: Process) : AutoCloseable {
    private val writer = process.outputStream.bufferedWriter()
    private val reader = process.inputStream.bufferedReader()
    private var nextId = 1

    /** Calls tool [name] and returns its text; throws if the tool reported an error. */
    fun callTool(name: String, arguments: JsonObject): String {
        val response = request("tools/call", buildJsonObject { put("name", name); put("arguments", arguments) })
        val result = response["result"] as? JsonObject ?: error("$name failed: ${response["error"]}")
        val text = result["content"].toString()
        check(result["isError"]?.toString() != "true") { "$name failed: $text" }
        return text
    }

    private fun request(method: String, params: JsonObject): JsonObject {
        val id = nextId++
        send(buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("method", method); put("params", params) })
        while (true) {
            val line = reader.readLine() ?: error("Kronstadt exited during $method")
            val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            if (message["id"]?.toString() == id.toString()) return message
        }
    }

    private fun send(message: JsonObject) {
        writer.write(message.toString())
        writer.newLine()
        writer.flush()
    }

    /** Closes Kronstadt's stdin, on which it exits and starts the Index Seed refresh (ADR 0005), and waits for it. */
    override fun close() {
        runCatching { writer.close() }
        if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    companion object {
        fun start(kronstadt: Path, worktree: Path, readinessTimeout: Duration, stderr: Path): McpClient {
            val process = ProcessBuilder(
                kronstadt.toString(), "--project-root", worktree.toString(),
                "--readiness-timeout", readinessTimeout.inWholeSeconds.toString(),
            ).directory(worktree.toFile()).redirectError(stderr.toFile()).start()
            val client = McpClient(process)
            client.request(
                "initialize",
                buildJsonObject {
                    put("protocolVersion", "2025-06-18")
                    put("capabilities", buildJsonObject {})
                    put("clientInfo", buildJsonObject { put("name", "kronstadt-benchmark"); put("version", "0") })
                },
            )
            client.send(buildJsonObject { put("jsonrpc", "2.0"); put("method", "notifications/initialized") })
            return client
        }
    }
}
