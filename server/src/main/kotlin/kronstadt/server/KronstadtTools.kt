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
import kronstadt.core.domain.SymbolKind
import kronstadt.core.port.FileStore

/**
 * MCP adapter: tool schemas plus thin handlers that parse arguments, call a use case, and turn its result
 * into text the agent can act on. No business logic lives here.
 */
internal class KronstadtTools(
    private val readStructure: ReadStructure,
    private val findSymbol: FindSymbol,
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
                "No symbols matching '${result.query}'" + (result.kind?.let { " of kind ${it.name}" } ?: "") +
                    ". If the project was just opened, the language server may still be indexing.",
            )
            is FindSymbolResult.InvalidQuery -> error(result.reason)
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
