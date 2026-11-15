package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindSymbolResult
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadStructureResult
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.ReadSymbolResult
import kronstadt.core.domain.SymbolKind
import kronstadt.core.port.FileStore
import kotlin.time.Duration

/**
 * MCP adapter: tool schemas plus thin handlers that parse arguments, call a use case, and turn its result
 * into text the agent can act on. No business logic lives here.
 */
internal class KronstadtTools(
    private val readStructure: ReadStructure,
    private val findSymbol: FindSymbol,
    private val readSymbol: ReadSymbol,
    private val files: FileStore,
) {
    fun registerOn(server: Server) {
        server.addTool(
            name = "kt_read_structure",
            description = "Returns the skeleton of a Kotlin file: classes, objects, interfaces and their member " +
                "declarations, without function bodies, each with its line span. Use this instead of reading a " +
                "whole file when you need an overview, then read only the lines you need.",
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
                "partial name across the project using the language server's index. Returns kind, file and line. " +
                "Prefer this over grep: it matches declarations only, not comments or call sites.",
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
                    stringProperty("file", "Path to the .kt/.kts file, absolute or relative to the project root.")
                    stringProperty(
                        "symbol",
                        "Symbol Path: enclosing declaration names separated by '/', e.g. 'OrderController/create'. " +
                            "Trailing segments are enough when unambiguous ('run', 'Nested/run'); a leading '/' " +
                            "anchors at the top level; add parameter types to pick an overload: 'parse(String, Int)'.",
                    )
                },
                required = listOf("file", "symbol"),
            ),
        ) { request -> handle(request) { readSymbol(it) } }
    }

    private suspend fun readStructure(args: JsonObject): CallToolResult {
        val path = args.string("filePath") ?: return error("Missing required argument: filePath")
        val xml = args.boolean("formatOutputAsXml") ?: true
        return when (val result = readStructure(path, xml)) {
            is ReadStructureResult.Structure -> text(result.text.ifBlank { "No declarations found in $path." })
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
                    result.symbols.forEach { s ->
                        append(s.kind.name).append(' ').append(s.name)
                        s.containerName?.let { append(" in ").append(it) }
                        append(" — ").append(files.display(s.file)).append(':').append(s.range.start.line + 1).append('\n')
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
        val path = args.string("file") ?: return error("Missing required argument: file")
        val symbol = args.string("symbol") ?: return error("Missing required argument: symbol")
        return when (val result = readSymbol(path, symbol)) {
            is ReadSymbolResult.Found -> text(result.text)
            is ReadSymbolResult.InvalidPath -> error("Invalid Symbol Path '${result.symbol}': ${result.reason}")
            is ReadSymbolResult.Ambiguous -> error(
                "'${result.symbol}' matches ${result.candidates.size} symbols in ${result.file}; pass one of:\n" +
                    result.candidates.joinToString("\n") { "  $it" },
            )
            is ReadSymbolResult.NotFound -> error(
                "No symbol '${result.symbol}' in ${result.file}." + if (result.suggestions.isEmpty()) {
                    " Use kt_read_structure to list its declarations."
                } else {
                    " Close names:\n" + result.suggestions.joinToString("\n") { "  $it" }
                },
            )
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
