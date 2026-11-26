package kronstadt.benchmark

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Tokens a session used, by kind. */
internal data class Tokens(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long) {
    /** The headline figure: all four kinds. */
    val total: Long get() = input + output + cacheRead + cacheWrite

    operator fun plus(other: Tokens): Tokens =
        Tokens(input + other.input, output + other.output, cacheRead + other.cacheRead, cacheWrite + other.cacheWrite)

    companion object {
        val ZERO: Tokens = Tokens(0, 0, 0, 0)
    }
}

/** The `result` event that ends a `claude -p` session. [reply] is the agent's final reply, null if it gave none. */
internal data class SessionResult(val subtype: String, val isError: Boolean, val reply: String?, val costUsd: Double?, val turns: Int?)

/** What a session printed: its result, if it got that far, and the tokens it used. */
internal data class SessionOutput(val result: SessionResult?, val tokens: Tokens)

/**
 * Reads the output of `claude -p --output-format stream-json --verbose`, one JSON event per line.
 *
 * The `result` event that ends a session carries its cost and tokens: those of every model it used (`modelUsage`, which
 * includes subagents and side calls), else its `usage`. A session killed before that, at the timeout, leaves only its
 * `assistant` events: their usage is summed, each message once with the last usage streamed for it, and the cost is
 * unknown. Lines that aren't JSON objects, such as one cut off by the kill, are skipped.
 */
internal object StreamJson {
    fun parse(lines: Sequence<String>): SessionOutput {
        val messages = LinkedHashMap<String, Tokens>()
        var result: JsonObject? = null
        for (line in lines) {
            val event = parseObject(line) ?: continue
            when (event.string("type")) {
                "assistant" -> {
                    val message = event["message"] as? JsonObject ?: continue
                    val usage = message["usage"] as? JsonObject ?: continue
                    messages[message.string("id") ?: "#${messages.size}"] = usage.tokens(SNAKE_CASE)
                }
                "result" -> result = event
            }
        }
        val final = result ?: return SessionOutput(null, messages.values.fold(Tokens.ZERO, Tokens::plus))
        val byModel = (final["modelUsage"] as? JsonObject)?.values?.filterIsInstance<JsonObject>().orEmpty()
        val tokens = if (byModel.isNotEmpty()) {
            byModel.map { it.tokens(CAMEL_CASE) }.fold(Tokens.ZERO, Tokens::plus)
        } else {
            (final["usage"] as? JsonObject)?.tokens(SNAKE_CASE) ?: Tokens.ZERO
        }
        val sessionResult = SessionResult(
            subtype = final.string("subtype") ?: "unknown",
            isError = (final["is_error"] as? JsonPrimitive)?.booleanOrNull ?: false,
            reply = final.string("result"),
            costUsd = (final["total_cost_usd"] as? JsonPrimitive)?.doubleOrNull,
            turns = (final["num_turns"] as? JsonPrimitive)?.intOrNull,
        )
        return SessionOutput(sessionResult, tokens)
    }

    /** The usage keys for input, output, cache reads and cache writes, as `usage` and `modelUsage` spell them. */
    private val SNAKE_CASE = listOf("input_tokens", "output_tokens", "cache_read_input_tokens", "cache_creation_input_tokens")
    private val CAMEL_CASE = listOf("inputTokens", "outputTokens", "cacheReadInputTokens", "cacheCreationInputTokens")

    private fun JsonObject.tokens(keys: List<String>): Tokens {
        val (input, output, cacheRead, cacheWrite) = keys.map { (this[it] as? JsonPrimitive)?.longOrNull ?: 0 }
        return Tokens(input, output, cacheRead, cacheWrite)
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun parseObject(line: String): JsonObject? =
        if (line.isBlank()) null else runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull()
}
