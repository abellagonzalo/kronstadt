package kronstadt.evaluation

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Writes fixture Claude Code transcripts in the on-disk shape Claude Code uses:
 * `<projects>/<project dir>/<session>.jsonl`, subagents in `<project dir>/<session>/subagents/agent-<id>.jsonl`,
 * one JSON record per line, tool calls as `tool_use` blocks in assistant records and their results as `tool_result`
 * blocks in the following user record. Noise records (attachments, system lines, prompts) are interleaved as in real
 * transcripts.
 */
internal class FixtureTranscripts(val projects: Path = Files.createTempDirectory("transcripts")) {
    fun session(
        id: String,
        cwd: String,
        start: String = "2026-11-10T09:00:00Z",
        block: Thread.() -> Unit,
    ) {
        val dir = projects.resolve(cwd.replace('/', '-').replace('.', '-')).createDirectories()
        val main = Thread(id, cwd, start, agentId = null).apply(block)
        dir.resolve("$id.jsonl").writeText(main.jsonl())
        main.subagents.forEach { sub ->
            val subDir = dir.resolve(id).resolve("subagents").createDirectories()
            subDir.resolve("agent-${sub.agentId}.jsonl").writeText(sub.jsonl())
        }
    }

    class Thread(private val sessionId: String, private val cwd: String, start: String, val agentId: String?) {
        private val lines = mutableListOf<JsonObject>()
        val subagents = mutableListOf<Thread>()
        private var seconds = java.time.Instant.parse(start).epochSecond
        private var counter = 0
        private var dispatched = 0

        init {
            lines += record("user", cwd) { put("message", buildJsonObject { put("role", "user"); put("content", "do the work") }) }
            lines += buildJsonObject { put("type", "permission-mode"); put("sessionId", sessionId); put("permissionMode", "auto") }
        }

        fun call(
            name: String,
            input: JsonObject,
            result: String = "ok",
            isError: Boolean = false,
            writeType: String? = null,
            mcpShaped: Boolean = name.startsWith("mcp__"),
            cwd: String = this.cwd,
            toolUseResult: JsonElement? = writeType?.let { buildJsonObject { put("type", it) } },
        ) {
            val id = "toolu_${agentId ?: "main"}_${counter++}"
            lines += record("assistant", cwd) {
                put(
                    "message",
                    buildJsonObject {
                        put("role", "assistant")
                        put(
                            "content",
                            buildJsonArray {
                                add(buildJsonObject { put("type", "text"); put("text", "Let me look.") })
                                add(buildJsonObject { put("type", "tool_use"); put("id", id); put("name", name); put("input", input) })
                            },
                        )
                    },
                )
            }
            lines += record("attachment", cwd) {
                put("attachment", buildJsonObject { put("type", "hook_success"); put("hookEvent", "PreToolUse"); put("toolUseID", id) })
            }
            val content: JsonElement =
                if (mcpShaped) buildJsonArray { add(buildJsonObject { put("type", "text"); put("text", result) }) } else JsonPrimitive(result)
            lines += record("user", cwd) {
                put(
                    "message",
                    buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("tool_use_id", id)
                                        put("type", "tool_result")
                                        put("content", content)
                                        if (isError) put("is_error", true)
                                    },
                                )
                            },
                        )
                    },
                )
                if (toolUseResult != null) put("toolUseResult", toolUseResult)
            }
        }

        fun read(path: String, result: String = "1\tpackage x") = call("Read", obj("file_path" to path), result)

        fun bash(command: String, result: String = "", isError: Boolean = false, cwd: String = this.cwd) =
            call("Bash", obj("command" to command), result, isError, cwd = cwd)

        fun grep(vararg args: Pair<String, String>) = call("Grep", obj(*args), "Found 1 file")

        fun glob(pattern: String) = call("Glob", obj("pattern" to pattern), "src/A.kt")

        fun write(path: String, type: String = "create") = call("Write", obj("file_path" to path, "content" to "class A"), "File written", writeType = type)

        fun edit(path: String, result: String = "The file has been updated", isError: Boolean = false) =
            call("Edit", obj("file_path" to path, "old_string" to "a", "new_string" to "b"), result, isError)

        fun kronstadt(tool: String, vararg args: Pair<String, String>, result: String = "ok", isError: Boolean = false) =
            call("mcp__kronstadt__$tool", obj(*args), result, isError)

        fun ktEdit(symbol: String, result: String, isError: Boolean = false) =
            call(
                "mcp__kronstadt__kt_edit",
                buildJsonObject {
                    put("operations", buildJsonArray { add(obj("type" to "replace", "symbol" to symbol, "code" to "fun f() = 1")) })
                },
                result,
                isError,
            )

        /**
         * A Code Mode `REPL` call as Claude Code 2.1.327 records it: the host call, whose input is the script and whose result
         * is `(dispatched #N)`, then each call the script made ([block]) as its own virtual records.
         */
        fun repl(code: String = "await \$`ls`", block: Repl.() -> Unit = {}) {
            val n = ++dispatched
            call("REPL", obj("code" to code), "(dispatched #$n)", mcpShaped = false, toolUseResult = buildJsonObject {
                put("code", code); put("result", "(dispatched #$n)"); put("stdout", ""); put("stderr", ""); put("evalId", n); put("asyncDispatched", true)
            })
            Repl().block()
        }

        /**
         * The calls a `REPL` script makes. Each is an `assistant` record with `isVirtual: true`, `model: "<synthetic>"` and a
         * `tool_use` id `repl_<uuid>`, then a virtual `user` record whose `tool_result` content is `""` on success, the text
         * being in the record's `toolUseResult` (an object with `stdout` for `Bash`, a string for MCP tools); on failure the
         * content holds the error and there is no `toolUseResult`.
         */
        inner class Repl {
            fun call(name: String, input: JsonObject, content: String = "", isError: Boolean = false, toolUseResult: JsonElement? = null) {
                val id = "repl_${java.util.UUID.randomUUID()}"
                lines += record("assistant", cwd) {
                    put("isVirtual", true)
                    put("message", buildJsonObject {
                        put("role", "assistant"); put("model", "<synthetic>")
                        put("content", buildJsonArray { add(buildJsonObject { put("type", "tool_use"); put("id", id); put("name", name); put("input", input) }) })
                    })
                }
                lines += record("user", cwd) {
                    put("isVirtual", true)
                    put("message", buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject { put("type", "tool_result"); put("tool_use_id", id); put("content", content); put("is_error", isError) })
                        })
                    })
                    if (toolUseResult != null) put("toolUseResult", toolUseResult)
                }
            }

            fun bash(command: String, stdout: String = "", stderr: String = "") = call(
                "Bash",
                obj("command" to command),
                toolUseResult = buildJsonObject {
                    put("stdout", stdout); put("stderr", stderr); put("interrupted", false); put("isImage", false); put("noOutputExpected", false)
                },
            )

            fun kronstadt(tool: String, vararg args: Pair<String, String>, result: String = "ok", isError: Boolean = false) =
                if (isError) call("mcp__kronstadt__$tool", obj(*args), content = result, isError = true)
                else call("mcp__kronstadt__$tool", obj(*args), toolUseResult = JsonPrimitive(result))
        }

        fun subagent(agentId: String, block: Thread.() -> Unit) {
            subagents += Thread(sessionId, cwd, java.time.Instant.ofEpochSecond(seconds).toString(), agentId).apply(block)
        }

        fun jsonl(): String = lines.joinToString("\n", postfix = "\n") { it.toString() }

        private fun record(type: String, cwd: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
            put("type", type)
            put("sessionId", sessionId)
            put("cwd", cwd)
            put("isSidechain", agentId != null)
            agentId?.let { put("agentId", it) }
            put("timestamp", java.time.Instant.ofEpochSecond(seconds++).toString())
            body()
        }
    }

    companion object {
        fun obj(vararg entries: Pair<String, String>): JsonObject = buildJsonObject { entries.forEach { (k, v) -> put(k, v) } }
    }
}
