@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kronstadt.core.application.Edit
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindUsages
import kronstadt.core.application.GetDefinition
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.RenameSymbol
import kronstadt.core.application.WriteCalls
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Validation
import kronstadt.lsp.LspCodeIntelligence
import kronstadt.workspace.LocalFileStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import java.io.BufferedWriter
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Measurement harness for #66: how Kronstadt answers many read calls fired at once, as a Code Mode script's
 * `Promise.all` sends them. Tool calls go through the MCP SDK's own dispatch (a [StdioServerTransport] over in-process
 * pipes) into the tools wired as in `Main.kt`, against a real kotlin-lsp on the contract-test fixture.
 *
 * Each workload runs at each concurrency, [REPETITIONS] times; the medians are written as a Markdown table to
 * `server/build/reports/parallel-reads.md` (and stdout). The assertions are about correctness only: no errors, no
 * timeouts, no leaked documents, and the edit visible once it completed. Timings are recorded, never asserted.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ParallelReadsContractTest {
    private lateinit var project: Path
    private lateinit var indexDir: Path
    private lateinit var lsp: LspCodeIntelligence
    private lateinit var client: McpClient
    private lateinit var server: Server

    @BeforeAll
    fun start() = runBlocking {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :server:contractTest" })
        project = Files.createTempDirectory("kronstadt-parallel-reads").toRealPath()
        fixture.copyToRecursively(project, followLinks = false, overwrite = true)
        val command = (System.getenv("KRONSTADT_LANGUAGE_SERVER") ?: "kotlin-lsp --stdio").trim().split(Regex("\\s+"))
        indexDir = Files.createTempDirectory("kronstadt-parallel-reads-index").toRealPath().resolve("kotlin-server")
        lsp = LspCodeIntelligence.start(command, project, indexDir)
        val readiness = lsp.awaitReady(5.minutes)
        check(readiness == Readiness.Ready) { "kotlin-lsp not ready: $readiness" }

        // Wired as in Main.kt, with kotlin-lsp already started.
        val files = LocalFileStore(project)
        val intelligence = DeferredCodeIntelligence(CompletableDeferred(Result.success(lsp)))
        val writeCalls = WriteCalls()
        val readinessTimeout = Readiness.DEFAULT_TIMEOUT
        server = Server(
            Implementation(name = "kronstadt", version = VERSION),
            ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            instructions = serverInstructions(files.root),
        )
        KronstadtTools(
            ReadStructure(files, intelligence),
            FindSymbol(files, intelligence, readinessTimeout),
            ReadSymbol(files, intelligence, readinessTimeout),
            FindUsages(files, intelligence, readinessTimeout),
            GetDefinition(files, intelligence, readinessTimeout),
            Edit(files, intelligence, readinessTimeout, Validation.DEFAULT_TIMEOUT, writeCalls),
            RenameSymbol(files, intelligence, readinessTimeout, Validation.DEFAULT_TIMEOUT, writeCalls),
        ).registerOn(server)

        val toServer = Pipe.open()
        val fromServer = Pipe.open()
        val transport = StdioServerTransport(
            Channels.newInputStream(toServer.source()).asSource().buffered(),
            Channels.newOutputStream(fromServer.sink()).asSink().buffered(),
        )
        server.createSession(transport)
        client = McpClient(Channels.newOutputStream(toServer.sink()), Channels.newInputStream(fromServer.source()))
        client.request(
            "initialize",
            Json.parseToJsonElement("""{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"parallel-reads","version":"0"}}""").jsonObject,
            CALL_TIMEOUT,
        )
        client.notify("notifications/initialized")
    }

    @AfterAll
    fun stop() {
        if (::server.isInitialized) runBlocking { runCatching { server.close() } }
        if (::client.isInitialized) client.close()
        if (::lsp.isInitialized) lsp.close()
        if (::project.isInitialized) project.deleteRecursively()
        if (::indexDir.isInitialized) indexDir.parent.deleteRecursively()
    }

    @Test
    fun `measures concurrent reads, and one kt_edit among them`() = runBlocking {
        // Warm-up: each workload once at N = 1, not recorded, so JIT and kotlin-lsp's first analyses aren't counted.
        WORKLOADS.forEach { runBatch(it, 1, repetition = 0) }

        val rows = mutableListOf<Row>()
        for (workload in WORKLOADS) {
            for (n in workload.concurrencies) {
                val batches = (1..REPETITIONS).map { runBatch(workload, n, it) }
                rows += Row(workload, n, batches)
            }
        }
        val report = report(rows)
        println(report)
        val out = Path.of(System.getProperty("user.dir")).resolve("build/reports").createDirectories().resolve("parallel-reads.md")
        out.writeText(report)

        val problems = rows.flatMap { row ->
            row.batches.flatMap { batch ->
                batch.calls.filter { it.outcome !is Outcome.Ok }.map { "${row.workload.id} N=${row.n} #${batch.repetition}: ${it.label}: ${it.outcome}" } +
                    batch.leaked.map { "${row.workload.id} N=${row.n} #${batch.repetition}: leaked $it" } +
                    listOfNotNull(batch.edit?.takeUnless { it.visibleAfter }?.let { "${row.workload.id} #${batch.repetition}: edit not visible after it completed" })
            }
        }
        assertTrue(problems.isEmpty(), "Problems under concurrency:\n" + problems.joinToString("\n") + "\n\n" + report)
    }

    /** Fires [n] calls of [workload] at once (for the edit workload, the edit after the first half), and awaits them all. */
    private suspend fun runBatch(workload: Workload, n: Int, repetition: Int): Batch = coroutineScope {
        val reads = workload.calls(n)
        val marker = "edit $repetition-${System.nanoTime()}"
        val start = TimeSource.Monotonic.markNow()
        var editCall: Deferred<Call>? = null
        val calls = mutableListOf<Deferred<Call>>()
        reads.forEachIndexed { i, call ->
            if (workload.withEdit && i == reads.size / 2) editCall = async(Dispatchers.IO) { client.call(editOf(marker)) }
            calls += async(Dispatchers.IO) { client.call(call) }
        }
        val results = calls.awaitAll()
        val edit = editCall?.await()
        val wall = start.elapsedNow()
        val editOutcome = edit?.let { written ->
            val after = client.call(ToolCall("kt_read_symbol", EDITED_FILE, EDITED_SYMBOL) { put("file", EDITED_FILE); put("symbol", EDITED_SYMBOL) })
            val stale = results.count { it.call.symbol == EDITED_SYMBOL && it.finishedAt > written.finishedAt && it.text?.contains(marker) == false }
            EditOutcome(
                succeeded = written.outcome is Outcome.Ok,
                visibleAfter = after.text?.contains(marker) == true,
                staleAfterWrite = stale,
            )
        }
        Batch(repetition, wall, results + listOfNotNull(edit), leakedDocuments(), editOutcome)
    }

    private fun editOf(marker: String) = ToolCall("kt_edit", EDITED_FILE, EDITED_SYMBOL) {
        putJsonArray("operations") {
            add(
                buildJsonObject {
                    put("type", "replace")
                    put("file", EDITED_FILE)
                    put("symbol", EDITED_SYMBOL)
                    put("code", "fun area(width: Double, height: Double): Area = width * height // $marker")
                },
            )
        }
    }

    /**
     * The documents the adapter still counts holders for, read reflectively: between batches no tool call runs, so
     * every open document must be a retained one with no holder (see `LspCodeIntelligence.OpenDocument`).
     */
    private fun leakedDocuments(): List<String> {
        val field = LspCodeIntelligence::class.java.getDeclaredField("openDocuments").apply { isAccessible = true }
        val open = (field.get(lsp) as Map<*, *>).toMap()
        return open.mapNotNull { (uri, document) ->
            val type = document!!::class.java
            val holders = type.getDeclaredField("holders").apply { isAccessible = true }.getInt(document)
            val retained = type.getDeclaredField("retained").apply { isAccessible = true }.getBoolean(document)
            if (holders == 0 && retained) null else "$uri (holders=$holders, retained=$retained)"
        }
    }

    private fun report(rows: List<Row>): String = buildString {
        appendLine("kotlin-lsp ${lsp.serverVersion}; ${Runtime.getRuntime().availableProcessors()} processors; median of $REPETITIONS repetitions")
        appendLine()
        appendLine("| Workload | N | Wall (ms) | Latency min (ms) | p50 (ms) | max (ms) | Errors | Timeouts | Leaked docs | Edit |")
        appendLine("|---|---:|---:|---:|---:|---:|---|---:|---:|---|")
        rows.forEach { row ->
            val errors = row.batches.flatMap { b -> b.calls.mapNotNull { (it.outcome as? Outcome.Failed)?.message } }
                .groupingBy { it }.eachCount().entries.joinToString("; ") { "${it.value}× ${it.key}" }.ifEmpty { "0" }
            val timeouts = row.batches.sumOf { b -> b.calls.count { it.outcome is Outcome.TimedOut } }
            val leaked = row.batches.sumOf { it.leaked.size }
            val edit = row.batches.mapNotNull { it.edit }.takeIf { it.isNotEmpty() }?.let { edits ->
                "${edits.count { it.succeeded }}/${edits.size} written in ${row.median { it.editLatency!! }} ms, " +
                    "${edits.count { it.visibleAfter }}/${edits.size} visible after, " +
                    "${edits.sumOf { it.staleAfterWrite }} stale reads after the write"
            } ?: ""
            append("| ${row.workload.id} | ${row.n} | ${row.median { it.wall }} ")
            append("| ${row.median { it.latencies.min() }} | ${row.median { it.latencies.percentile(50) }} | ${row.median { it.latencies.max() }} ")
            appendLine("| $errors | $timeouts | $leaked | $edit |")
        }
        appendLine()
        WORKLOADS.forEach { appendLine("- ${it.id}: ${it.description}") }
    }

    private class Workload(
        val id: String,
        val description: String,
        val concurrencies: List<Int> = listOf(1, 10, 30),
        val withEdit: Boolean = false,
        val calls: (Int) -> List<ToolCall>,
    )

    /** One tool call to send; [symbol] and [file] only label it. */
    private class ToolCall(val tool: String, val file: String?, val symbol: String?, val arguments: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        val label get() = "$tool ${listOfNotNull(file?.substringAfterLast('/'), symbol).joinToString(" ")}"
    }

    private sealed interface Outcome {
        data object Ok : Outcome
        data class Failed(val message: String) : Outcome
        data object TimedOut : Outcome
    }

    private class Call(val call: ToolCall, val outcome: Outcome, val latency: Duration, val finishedAt: TimeSource.Monotonic.ValueTimeMark, val text: String?) {
        val label get() = call.label
    }

    private class EditOutcome(val succeeded: Boolean, val visibleAfter: Boolean, val staleAfterWrite: Int)

    private class Batch(val repetition: Int, val wall: Duration, val calls: List<Call>, val leaked: List<String>, val edit: EditOutcome?) {
        /** The reads' latencies; the edit's own isn't a read's. */
        val latencies get() = calls.filter { it.call.tool != "kt_edit" }.map { it.latency }

        val editLatency get() = calls.firstOrNull { it.call.tool == "kt_edit" }?.latency
    }

    private class Row(val workload: Workload, val n: Int, val batches: List<Batch>) {
        fun median(of: (Batch) -> Duration): Long = batches.map(of).sorted()[batches.size / 2].inWholeMilliseconds
    }

    /**
     * A minimal MCP client: writes JSON-RPC requests one per line and completes each from the reader thread when its
     * answer arrives, so any number can be in flight at once.
     */
    private class McpClient(toServer: OutputStream, fromServer: InputStream) : AutoCloseable {
        private val writer: BufferedWriter = toServer.bufferedWriter()
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()
        private val ids = AtomicInteger()
        private val reader = thread(isDaemon = true, name = "parallel-reads-client") {
            runCatching {
                fromServer.bufferedReader().forEachLine { line ->
                    val message = runCatching { Json.parseToJsonElement(line).jsonObject }.getOrNull() ?: return@forEachLine
                    if ("method" in message) return@forEachLine
                    val id = (message["id"] as? JsonPrimitive)?.intOrNull ?: return@forEachLine
                    pending.remove(id)?.complete(message)
                }
            }
        }

        suspend fun request(method: String, params: JsonObject, timeout: Duration): JsonObject? {
            val id = ids.incrementAndGet()
            val answer = CompletableDeferred<JsonObject>()
            pending[id] = answer
            val message = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id)
                put("method", method)
                put("params", params)
            }
            withContext(Dispatchers.IO) { write(message.toString()) }
            return withTimeoutOrNull(timeout) { answer.await() }.also { if (it == null) pending.remove(id) }
        }

        fun notify(method: String) = write("""{"jsonrpc":"2.0","method":"$method"}""")

        suspend fun call(call: ToolCall): Call {
            val params = buildJsonObject {
                put("name", call.tool)
                putJsonObject("arguments", call.arguments)
            }
            val start = TimeSource.Monotonic.markNow()
            val answer = request("tools/call", params, CALL_TIMEOUT)
            val finishedAt = TimeSource.Monotonic.markNow()
            val latency = finishedAt - start
            if (answer == null) return Call(call, Outcome.TimedOut, latency, finishedAt, null)
            (answer["error"] as? JsonObject)?.let { error ->
                val message = (error["message"] as? JsonPrimitive)?.contentOrNull ?: error.toString()
                return Call(call, Outcome.Failed("protocol error: $message"), latency, finishedAt, null)
            }
            val result = answer["result"] as JsonObject
            val text = (result["content"] as? JsonArray).orEmpty()
                .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }.joinToString("\n")
            val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull == true
            val outcome = when {
                !isError && !(call.tool == "kt_edit" && text.startsWith("Edit rejected")) -> Outcome.Ok
                TIMEOUT_TEXT.containsMatchIn(text) -> Outcome.TimedOut
                else -> Outcome.Failed(text.lineSequence().first().take(160))
            }
            return Call(call, outcome, latency, finishedAt, text)
        }

        @Synchronized
        private fun write(line: String) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }

        override fun close() {
            runCatching { writer.close() }
            reader.join(1_000)
        }
    }

    private companion object {
        const val REPETITIONS = 3

        /** Longer than any per-request or readiness timeout in Kronstadt, so those show up as the tool's own errors. */
        val CALL_TIMEOUT = 3.minutes

        /** Tool errors that mean something timed out or wasn't ready, rather than failed. */
        val TIMEOUT_TEXT = Regex("(?i)timed out|timeout|still indexing")

        const val SHAPES = "src/main/kotlin/fixture/Shapes.kt"
        const val SPANS = "src/main/kotlin/fixture/Spans.kt"
        const val INITIALIZERS = "src/main/kotlin/fixture/Initializers.kt"
        const val MUTABILITY = "src/main/kotlin/fixture/Mutability.kt"
        const val DEFINITIONS = "src/main/kotlin/fixture/Definitions.kt"
        const val SYM = "src/main/kotlin/fixture/gamma/Sym.kt"
        const val ALPHA = "src/main/kotlin/fixture/alpha/Shape.kt"
        const val BETA = "src/main/kotlin/fixture/beta/Shape.kt"

        const val EDITED_FILE = SHAPES
        const val EDITED_SYMBOL = "area"

        /** Distinct Symbols, interleaved across the fixture's files; the first ten include the edited one. */
        val SYMBOLS: List<Pair<String, String>> = listOf(
            SHAPES to "Calculator", SPANS to "Documented", INITIALIZERS to "Initialized", MUTABILITY to "Mutability",
            DEFINITIONS to "Definitions", SYM to "Sym", ALPHA to "Shape", BETA to "Shape", SPANS to "Color", SHAPES to "area",
            SHAPES to "Outer", SPANS to "Box", INITIALIZERS to "Checked", MUTABILITY to "Mutability/withLocals",
            DEFINITIONS to "Definitions/uses", SHAPES to "Operation", SPANS to "Widget", MUTABILITY to "topLevelVal",
            SHAPES to "Outer/Nested", SPANS to "Hidden", MUTABILITY to "topLevelVar", SHAPES to "Outer/Factory",
            SPANS to "Formatter", SHAPES to "String.shout", SPANS to "Sized", SHAPES to "String.lastChar", SPANS to "Tint",
            SHAPES to "Calculator/sumOfSquares", SPANS to "Crate", SPANS to "Documented/blocky",
            SPANS to "Hidden/outer",
        )

        val QUERIES = listOf("Calculator", "Shape", "Documented", "Color", "Box", "Widget", "area", "describe", "Sym", "Hidden")
        val STRUCTURES = listOf(SHAPES, SPANS, INITIALIZERS, MUTABILITY, DEFINITIONS, SYM, ALPHA, BETA)

        /** Names used exactly once in their Symbol. */
        val NAMES: List<Triple<String, String, String>> = listOf(
            Triple(DEFINITIONS, "Definitions/uses", "Documented"), Triple(SPANS, "Box/area", "width"),
            Triple(DEFINITIONS, "Definitions/uses", "listOf"), Triple(DEFINITIONS, "Definitions/uses", "File"),
            Triple(DEFINITIONS, "Definitions/uses", "blocky"), Triple(SPANS, "Box/area", "height"),
            Triple(DEFINITIONS, "Definitions/uses", "shout"), Triple(DEFINITIONS, "Definitions/uses", "toInt"),
            Triple(DEFINITIONS, "Definitions/uses", "area"),
        )

        fun readSymbol(file: String, symbol: String) = ToolCall("kt_read_symbol", file, symbol) { put("file", file); put("symbol", symbol) }

        val WORKLOADS = listOf(
            Workload("a", "kt_read_symbol on N different Symbols over 8 files") { n ->
                SYMBOLS.take(n).map { (file, symbol) -> readSymbol(file, symbol) }
            },
            Workload("b", "kt_read_symbol on the same Symbol (Calculator) N times") { n ->
                List(n) { readSymbol(SHAPES, "Calculator") }
            },
            Workload("c", "kt_find_usages on N different Symbols (with declaredIn)") { n ->
                SYMBOLS.take(n).map { (file, symbol) ->
                    ToolCall("kt_find_usages", file, symbol) { put("declaredIn", file); put("symbol", symbol) }
                }
            },
            Workload("d", "kt_find_symbol, kt_read_structure and kt_get_definition in turn") { n ->
                List(n) { i ->
                    when (i % 3) {
                        0 -> QUERIES[i / 3 % QUERIES.size].let { q -> ToolCall("kt_find_symbol", null, q) { put("query", q) } }
                        1 -> STRUCTURES[i / 3 % STRUCTURES.size].let { f -> ToolCall("kt_read_structure", f, null) { put("file", f) } }
                        else -> NAMES[i / 3 % NAMES.size].let { (file, symbol, name) ->
                            ToolCall("kt_get_definition", file, "$symbol:$name") { put("file", file); put("symbol", symbol); put("name", name) }
                        }
                    }
                }
            },
            Workload("e", "workload a at N = 10, with one kt_edit replace of Shapes.kt's area fired after the 5th read", listOf(10), withEdit = true) { n ->
                SYMBOLS.take(n).map { (file, symbol) -> readSymbol(file, symbol) }
            },
        )

        fun List<Duration>.percentile(p: Int): Duration = sorted()[((size - 1) * p + 50) / 100]
    }
}
