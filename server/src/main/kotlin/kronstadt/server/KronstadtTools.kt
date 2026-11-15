package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kronstadt.core.application.Edit
import kronstadt.core.application.EditResult
import kronstadt.core.application.EditedFile
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindSymbolResult
import kronstadt.core.application.OperationFailure
import kronstadt.core.application.Problem
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadStructureResult
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.ReadSymbolResult
import kronstadt.core.application.Replace
import kronstadt.core.application.SymbolCandidate
import kronstadt.core.domain.SymbolKind
import kotlin.time.Duration

/**
 * MCP adapter: tool schemas plus thin handlers that parse arguments, call a use case, and turn its result
 * into text the agent can act on. No business logic lives here.
 */
internal class KronstadtTools(
    private val readStructure: ReadStructure,
    private val findSymbol: FindSymbol,
    private val readSymbol: ReadSymbol,
    private val edit: Edit,
) {
    fun registerOn(server: Server) {
        server.addTool(
            name = "kt_read_structure",
            description = "Returns the skeleton of a Kotlin file: every declaration, nested under its container, " +
                "without bodies. Each line shows the kind, the canonical Symbol Path (pass it as-is to kt_read_symbol " +
                "and the other tools), the signature (so overloads look different) and the line span. Use this " +
                "instead of reading a whole file when you need an overview, then kt_read_symbol what you need.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    stringProperty("filePath", "Path to a .kt/.kts file, absolute or relative to the project root.")
                    putJsonObject("formatOutputAsXml") {
                        put("type", "boolean")
                        put("description", "Wrap the result in <file_structure> tags. Default: true.")
                    }
                },
                required = listOf("filePath"),
            ),
        ) { request -> handle(request) { readStructure(it) } }

        server.addTool(
            name = "kt_find_symbol",
            description = "Finds Kotlin declarations (classes, interfaces, functions, properties...) by exact or " +
                "partial name (case-insensitive) in the project's own source, using the language server's index. Results " +
                "are grouped by file; each line shows the kind, the canonical Symbol Path (pass it with its file to " +
                "kt_read_symbol and the other tools) and the line span. Prefer this over grep: it matches " +
                "declarations only, not comments or call sites.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    stringProperty("query", "Exact or partial symbol name, e.g. 'UserRepository' or 'calculateTax'.")
                    putJsonObject("symbolType") {
                        put("type", "string")
                        putJsonArray("enum") { SymbolKind.entries.forEach { add(it.name) } }
                        put("description", "Optional: only return symbols of this kind.")
                    }
                    putJsonObject("limit") {
                        put("type", "integer")
                        put("description", "Maximum results. Default: ${FindSymbol.DEFAULT_LIMIT}.")
                    }
                },
                required = listOf("query"),
            ),
        ) { request -> handle(request) { findSymbol(it) } }

        server.addTool(
            name = "kt_read_symbol",
            description = "Returns one Kotlin declaration's exact source: its KDoc or comments above, annotations, " +
                "modifiers, the declaration with its body and nested members, and an end-of-line comment, as whole " +
                "lines. A header line gives the canonical Symbol Path, file and line span; the source follows " +
                "without line numbers, so it can be copied verbatim. Use this instead of reading a whole file.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    stringProperty(
                        "file",
                        "Optional path to the .kt/.kts file, absolute or relative to the project root. Without it the " +
                            "Symbol Path is resolved across the project (waits for indexing).",
                    )
                    stringProperty(
                        "symbol",
                        "Symbol Path: enclosing declaration names separated by '/', e.g. 'OrderController/create'. " +
                            "Trailing segments are enough when unambiguous ('run', 'Nested/run'); a leading '/' " +
                            "anchors at the top level; add parameter types to pick an overload: 'parse(String, Int)'. " +
                            "Without a file, a dotted package may come first: 'com.example.a/Config' (a single-word first " +
                            "segment is always a name; for single-word packages pass the file).",
                    )
                },
                required = listOf("symbol"),
            ),
        ) { request -> handle(request) { readSymbol(it) } }

        server.addTool(
            name = "kt_edit",
            description = "Changes Kotlin code by Symbol, validated against the compiler before anything is written. " +
                "A 'replace' Operation swaps a Symbol's whole span (exactly what kt_read_symbol returns: KDoc or " +
                "comments above, annotations, modifiers, declaration, end-of-line comment) for your code; omitted " +
                "KDoc or annotations are deleted. The code may rename the declaration or hold several. It is " +
                "re-indented to the target's column, so indentation needn't match. The file is written only if the " +
                "edit adds no compiler errors to it; otherwise nothing is written and the new errors are listed. " +
                "Currently takes exactly one replace Operation.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("operations") {
                        put("type", "array")
                        put("description", "The Operations of this Edit, in order. Currently exactly one.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("type") {
                                    put("type", "string")
                                    putJsonArray("enum") { add("replace") }
                                    put("description", "The Operation.")
                                }
                                stringProperty(
                                    "symbol",
                                    "Symbol Path of the target, as kt_read_symbol takes it, e.g. 'OrderController/create'.",
                                )
                                stringProperty(
                                    "file",
                                    "Optional path to the target's .kt/.kts file, absolute or relative to the project " +
                                        "root. Without it the Symbol Path is resolved across the project.",
                                )
                                stringProperty("code", "The complete new source for the Symbol's whole span.")
                            }
                            putJsonArray("required") {
                                add("type")
                                add("symbol")
                                add("code")
                            }
                        }
                    }
                },
                required = listOf("operations"),
            ),
        ) { request -> handle(request) { edit(it) } }
    }

    private suspend fun edit(args: JsonObject): CallToolResult {
        val operations = args["operations"] as? JsonArray ?: return error("Missing required argument: operations (an array)")
        if (operations.size != 1) return error("kt_edit currently takes exactly one Operation; got ${operations.size}.")
        val operation = operations.single() as? JsonObject ?: return error("Operation 0 must be an object.")
        val type = operation.string("type") ?: return error("Operation 0: missing 'type'.")
        if (type != "replace") return error("Operation 0: unsupported type '$type'; kt_edit currently supports 'replace'.")
        val symbol = operation.string("symbol") ?: return error("Operation 0: missing 'symbol'.")
        val code = operation.string("code") ?: return error("Operation 0: missing 'code'.")
        val file = operation.string("file")?.takeIf { it.isNotBlank() }
        return when (val result = edit(Replace(file, symbol, code))) {
            is EditResult.Written -> text(result.files.joinToString("\n") { written(it) })
            is EditResult.Rejected -> error(
                buildString {
                    val count = result.newErrors.size
                    append("Edit rejected: $count new error${if (count == 1) "" else "s"}; nothing written.\n")
                    result.newErrors.forEach { append(problem(it, withFile = true)) }
                    append("fix and resend the whole batch")
                },
            )
            is EditResult.ChangedOnDisk -> error("${result.file} changed on disk since read; re-read and retry")
            EditResult.ValidationTimedOut -> error("validation timed out; nothing written")
            is EditResult.NotReady -> notReady(result.phase, result.elapsed)
            is EditResult.OperationFailed -> error("Operation ${result.index}: ${failure(result.failure)} Nothing written.")
        }
    }

    /** One file's outcome: the Symbols the edit left, then new warnings and the errors it already had. */
    private fun written(file: EditedFile) = buildString {
        append("Edited ").append(file.file).append('\n')
        file.symbols.forEach { append("  ").append(it.path).append("  // L").append(it.firstLine).append('-').append(it.lastLine).append('\n') }
        if (file.newWarnings.isNotEmpty()) {
            append("New warnings:\n")
            file.newWarnings.forEach { append(problem(it, withFile = false)) }
            if (file.moreWarnings > 0) append("  (${file.moreWarnings} more)\n")
        }
        if (file.preexistingErrors > 0) {
            append("${file.preexistingErrors} error${if (file.preexistingErrors == 1) " was" else "s were"} already in the file.\n")
        }
    }.trimEnd()

    private fun problem(problem: Problem, withFile: Boolean) = buildString {
        append("  ")
        if (withFile) append(problem.file).append(':')
        append('L').append(problem.line)
        append(" in ").append(problem.symbol ?: "(file level)")
        append(": ").append(problem.message).append('\n')
        append("      ").append(problem.source).append('\n')
    }

    private fun failure(failure: OperationFailure): String = when (failure) {
        is OperationFailure.InvalidPath -> "invalid Symbol Path '${failure.symbol}': ${failure.reason}."
        is OperationFailure.Ambiguous ->
            "'${failure.symbol}' matches ${failure.candidates.size} symbols${failure.file.inFile()}; pass one of" +
                (if (failure.file == null) " these paths with its file" else "") + ":\n" +
                candidates(failure.candidates, withFiles = failure.file == null) + "\n"
        is OperationFailure.NotFound -> "no symbol '${failure.symbol}'${failure.file.inFile()}." + when {
            failure.suggestions.isNotEmpty() -> " Close names:\n" + candidates(failure.suggestions, withFiles = failure.file == null) + "\n"
            failure.file != null -> " Use kt_read_structure to list its declarations."
            else -> " Use kt_find_symbol to search by partial name."
        }
        is OperationFailure.OutOfSync ->
            "index out of sync for ${failure.symbol} in ${failure.file}: its name is not where kotlin-lsp says; retry."
        is OperationFailure.SharedLine ->
            "${failure.symbol} shares line ${failure.line} of ${failure.file} with other code, so it has no whole-line " +
                "span; replace the enclosing symbol instead."
        is OperationFailure.FileNotFound -> "file not found: ${failure.path}."
        is OperationFailure.NotKotlin -> "not a Kotlin file: ${failure.path}. kt_edit only supports .kt and .kts files."
        is OperationFailure.OutsideProject -> "path is outside the project root: ${failure.path}."
    }

    private suspend fun readStructure(args: JsonObject): CallToolResult {
        val path = args.string("filePath") ?: return error("Missing required argument: filePath")
        val xml = args.boolean("formatOutputAsXml") ?: true
        return when (val result = readStructure(path, xml)) {
            is ReadStructureResult.Structure -> text(result.text)
            is ReadStructureResult.NoDeclarations -> text("No declarations found in ${result.path}.")
            is ReadStructureResult.FileNotFound -> error("File not found: ${result.path}")
            is ReadStructureResult.NotKotlin -> error("Not a Kotlin file: ${result.path}. kt_read_structure only supports .kt and .kts files.")
            is ReadStructureResult.OutsideProject -> error("Path is outside the project root: ${result.path}")
        }
    }

    private suspend fun findSymbol(args: JsonObject): CallToolResult {
        val query = args.string("query") ?: return error("Missing required argument: query")
        val kind = args.string("symbolType")?.let { name ->
            SymbolKind.entries.find { it.name == name.uppercase() }
                ?: return error("Unknown symbolType '$name'. Use one of: ${SymbolKind.entries.joinToString()}")
        }
        val limit = args.int("limit") ?: FindSymbol.DEFAULT_LIMIT
        return when (val result = findSymbol(query, kind, limit)) {
            is FindSymbolResult.Found -> text(
                buildString {
                    // One header per file, so a broad query doesn't repeat the package and path on every line.
                    result.files.forEach { group ->
                        append(group.file).append('\n')
                        group.symbols.forEach { s ->
                            append("  ")
                            // A constructor's path already says `constructor`.
                            if (s.kind != SymbolKind.CONSTRUCTOR) append(s.kind.keyword).append(' ')
                            append(s.path).append("  // L").append(s.firstLine).append('-').append(s.lastLine).append('\n')
                        }
                    }
                    if (result.truncated) append("(more results omitted; refine the query or raise limit)\n")
                }.trimEnd(),
            )
            is FindSymbolResult.NoMatches -> text(
                "No symbols matching '${result.query}'" + (result.kind?.let { " of kind ${it.name}" } ?: "") + ".",
            )
            is FindSymbolResult.InvalidQuery -> error(result.reason)
            is FindSymbolResult.NotReady -> notReady(result.phase, result.elapsed)
        }
    }

    private suspend fun readSymbol(args: JsonObject): CallToolResult {
        val path = args.string("file")?.takeIf { it.isNotBlank() }
        val symbol = args.string("symbol") ?: return error("Missing required argument: symbol")
        return when (val result = readSymbol(path, symbol)) {
            is ReadSymbolResult.Found -> text(result.text)
            is ReadSymbolResult.InvalidPath -> error("Invalid Symbol Path '${result.symbol}': ${result.reason}")
            is ReadSymbolResult.Ambiguous -> error(
                "'${result.symbol}' matches ${result.candidates.size} symbols${result.file.inFile()}; pass one of" +
                    (if (result.file == null) " these paths with its file" else "") + ":\n" +
                    candidates(result.candidates, withFiles = result.file == null),
            )
            is ReadSymbolResult.NotFound -> error(
                "No symbol '${result.symbol}'${result.file.inFile()}." + when {
                    result.suggestions.isNotEmpty() ->
                        " Close names:\n" + candidates(result.suggestions, withFiles = result.file == null)
                    result.file != null -> " Use kt_read_structure to list its declarations."
                    else -> " Use kt_find_symbol to search by partial name."
                },
            )
            is ReadSymbolResult.NotReady -> notReady(result.phase, result.elapsed)
            is ReadSymbolResult.OutOfSync -> error(
                "index out of sync for ${result.symbol} in ${result.file}: its name is not where kotlin-lsp says; nothing returned, retry",
            )
            is ReadSymbolResult.SharedLine -> error(
                "${result.symbol} shares line ${result.line} of ${result.file} with other code, so it has no whole-line " +
                    "span; read the enclosing symbol instead",
            )
            is ReadSymbolResult.FileNotFound -> error("File not found: ${result.path}")
            is ReadSymbolResult.NotKotlin -> error("Not a Kotlin file: ${result.path}. kt_read_symbol only supports .kt and .kts files.")
            is ReadSymbolResult.OutsideProject -> error("Path is outside the project root: ${result.path}")
        }
    }

    private fun String?.inFile() = this?.let { " in $it" } ?: ""

    /** One candidate per line; resolved project-wide, each with the file to pass along with its path. */
    private fun candidates(candidates: List<SymbolCandidate>, withFiles: Boolean) =
        candidates.joinToString("\n") { if (withFiles) "  ${it.path} — ${it.file}" else "  ${it.path}" }

    /** Infrastructure failures (language server down, timeouts) become tool errors instead of protocol errors. */
    private suspend fun handle(request: CallToolRequest, block: suspend (JsonObject) -> CallToolResult): CallToolResult =
        try {
            block(request.arguments ?: JsonObject(emptyMap()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error("${e::class.simpleName}: ${e.message}")
        }

    private fun notReady(phase: String, elapsed: Duration) =
        error("kotlin-lsp still indexing (phase $phase, ${elapsed.inWholeSeconds} s elapsed); nothing returned/written, retry")

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))

    private fun error(s: String) = CallToolResult(content = listOf(TextContent(s)), isError = true)

    private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.boolean(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}
