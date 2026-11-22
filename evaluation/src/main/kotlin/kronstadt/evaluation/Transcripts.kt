package kronstadt.evaluation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines

/** One tool call an agent made, paired with its result. */
internal data class ToolCall(
    val name: String,
    val input: JsonObject,
    /** The agent's working directory when it made the call. */
    val cwd: Path,
    /** `null` for the main agent, else the subagent's id: calls follow each other only within one thread. */
    val thread: String?,
    val timestamp: Instant,
    val result: String?,
    val isError: Boolean,
) {
    fun string(key: String): String? = (input[key] as? JsonPrimitive)?.contentOrNull
}

/** A Claude Code session: its main transcript and its subagents' transcripts, calls in time order. */
internal data class Session(val id: String, val cwd: Path, val start: Instant, val calls: List<ToolCall>)

/** Reads every `*.jsonl` transcript under Claude Code's projects directory (`~/.claude/projects`). */
internal object Transcripts {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(projects: Path): List<Session> {
        val files = Files.walk(projects).use { paths -> paths.filter { it.isRegularFile() && it.extension == "jsonl" }.sorted().toList() }
        return files.flatMap { read(it) }
            .groupBy { it.sessionId }
            .mapNotNull { (id, threads) -> session(id, threads) }
            .sortedBy { it.start }
    }

    private class Thread(val sessionId: String, val isMain: Boolean, val cwd: Path?, val start: Instant?, val calls: List<ToolCall>)

    private fun session(id: String, threads: List<Thread>): Session? {
        val main = threads.firstOrNull { it.isMain } ?: threads.first()
        val cwd = main.cwd ?: return null
        val start = threads.mapNotNull { it.start }.minOrNull() ?: return null
        return Session(id, cwd, start, threads.flatMap { it.calls }.sortedBy { it.timestamp })
    }

    /** A file may hold several sessions' records (a resumed session); split it by session id. */
    private fun read(file: Path): List<Thread> {
        val records = file.readLines().mapNotNull { line -> runCatching { json.parseToJsonElement(line).jsonObject }.getOrNull() }
        return records.filter { it.str("sessionId") != null }.groupBy { it.str("sessionId")!! }.map { (sessionId, recs) -> thread(sessionId, recs) }
    }

    private fun thread(sessionId: String, records: List<JsonObject>): Thread {
        val agentId = records.firstNotNullOfOrNull { it.str("agentId") }
        val results = mutableMapOf<String, Pair<String, Boolean>>()
        for (record in records.filter { it.str("type") == "user" }) {
            for (block in record.contentBlocks().filter { it.str("type") == "tool_result" }) {
                val id = block.str("tool_use_id") ?: continue
                val isError = (block["is_error"] as? JsonPrimitive)?.contentOrNull == "true"
                results[id] = text(block["content"]).ifEmpty { toolUseResultText(record["toolUseResult"]) } to isError
            }
        }
        val calls = records.filter { it.str("type") == "assistant" }.flatMap { record ->
            val cwd = record.str("cwd")?.let(Path::of) ?: return@flatMap emptyList()
            val timestamp = record.str("timestamp")?.let(Instant::parse) ?: return@flatMap emptyList()
            record.contentBlocks().filter { it.str("type") == "tool_use" }.mapNotNull { block ->
                val id = block.str("id") ?: return@mapNotNull null
                val result = results[id]
                ToolCall(
                    name = block.str("name") ?: return@mapNotNull null,
                    input = block["input"] as? JsonObject ?: JsonObject(emptyMap()),
                    cwd = cwd,
                    thread = agentId,
                    timestamp = timestamp,
                    result = result?.first,
                    isError = result?.second ?: false,
                )
            }
        }
        val first = records.firstOrNull { it.str("cwd") != null }
        return Thread(
            sessionId = sessionId,
            isMain = agentId == null,
            cwd = first?.str("cwd")?.let(Path::of),
            start = records.firstNotNullOfOrNull { it.str("timestamp") }?.let(Instant::parse),
            calls = calls,
        )
    }

    private fun JsonObject.contentBlocks(): List<JsonObject> =
        ((this["message"] as? JsonObject)?.get("content") as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

    /** A tool result is either a string or an array of content blocks; keep the text. */
    private fun text(content: Any?): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { (it as? JsonObject)?.str("text") }.joinToString("\n")
        else -> ""
    }

    /**
     * The text of a record's `toolUseResult`, which stands in for an empty `tool_result` content, as in calls a Code Mode
     * `REPL` script makes: a string for MCP tools, an object with `stdout` and `stderr` for `Bash`.
     */
    private fun toolUseResultText(value: Any?): String = when (value) {
        is JsonObject -> listOfNotNull(value.str("stdout"), value.str("stderr")).filter { it.isNotEmpty() }.joinToString("\n")
        else -> text(value)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
