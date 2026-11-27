package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
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
import kronstadt.core.domain.SymbolKind

/**
 * MCP adapter: tool schemas plus thin handlers that parse arguments, call a use case, and turn its result
 * into text the agent can act on. No business logic lives here.
 */
internal class KronstadtTools(
    private val readStructure: ReadStructure,
    private val findSymbol: FindSymbol,
    private val readSymbol: ReadSymbol,
    private val findUsages: FindUsages,
    private val getDefinition: GetDefinition,
    private val edit: Edit,
    private val renameSymbol: RenameSymbol,
) {
    fun registerOn(server: Server) {
        // Builds a failure's Reply once and drops it, only to load the classes each handler's catch uses now, while the
        // install is the one the server started from (#199).
        Replies.thrown(NoClassDefFoundError()).toResult()

        server.register(
            name = "kt_read_structure",
            description = "Returns the skeleton of a Kotlin file: every declaration, nested under its container, " +
                "without bodies. Each line shows the kind, the canonical Symbol Path (pass it as-is to kt_read_symbol " +
                "and the other tools), the signature (so overloads look different) and the line span. Use this " +
                "instead of reading a whole file when you need an overview, then kt_read_symbol what you need.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    stringProperty("file", "Path to a .kt/.kts file, absolute or relative to the project root.")
                },
                required = listOf("file"),
            ),
        ) { readStructure(it) }

        server.register(
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
        ) { findSymbol(it) }

        server.register(
            name = "kt_read_symbol",
            description = "Returns one Kotlin declaration's exact source: its KDoc or comments above, annotations, " +
                "modifiers, the declaration with its body and nested members, a trailing list comma and an " +
                "end-of-line comment, as whole lines. A header line gives the canonical Symbol Path, file and line " +
                "span; the source follows without line numbers, so it can be copied verbatim. Use this instead of " +
                "reading a whole file.",
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
        ) { readSymbol(it) }

        server.register(
            name = "kt_find_usages",
            description = "Lists every Usage of one Kotlin Symbol in the project's main and test source (not overrides or " +
                "implementations), using the language server's index. A summary line comes first; then each file (main " +
                "before test), the canonical Symbol Path containing its Usages (pass it with the file to kt_read_symbol " +
                "or kt_edit; '(file level)' outside any Symbol) and one line per Usage. Imports are only counted. At most " +
                "${FindUsages.MAX_USAGES} Usages, whole files only; the files left out are named, pass one as 'file' to see it. " +
                "'declaredIn' is the file that declares the Symbol, to resolve it there instead of across the project; " +
                "'file' only filters which Usages are listed. Prefer this over grep: it finds real Usages only, not " +
                "same-named text.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    stringProperty(
                        "symbol",
                        "Symbol Path of the Symbol, as kt_read_symbol takes it, resolved across the project unless " +
                            "'declaredIn' is given, e.g. " +
                            "'OrderController/create' or 'com.example.a/Config'.",
                    )
                    stringProperty(
                        "file",
                        "Optional .kt/.kts file, absolute or relative to the project root: list only the Usages in it.",
                    )
                    stringProperty(
                        "declaredIn",
                        "Optional .kt/.kts file, absolute or relative to the project root, that declares the Symbol: " +
                            "the Symbol Path is resolved in it, as kt_read_symbol resolves a path with a file, not " +
                            "across the project. Unlike 'file', it doesn't filter the Usages.",
                    )
                },
                required = listOf("symbol"),
            ),
        ) { findUsages(it) }

        server.register(
            name = "kt_get_definition",
            description = "Finds where a name used inside a Kotlin Symbol is declared, without computing positions: pass " +
                "the Symbol you are reading and the name. A project declaration comes back as a header (canonical Symbol " +
                "Path, file, line span) and its KDoc and declaration without the body; call kt_read_symbol for the rest. A " +
                "local variable or parameter gives the Symbol it is local to and its declaration line. A library or JDK " +
                "declaration gives its fully qualified name, jar, and KDoc and declaration from the sources jar; it is " +
                "read-only. Several definitions (e.g. expect/actual) are each shown. A keyword, literal or unresolved " +
                "name is reported as such, with the compiler's error if any. Waits for indexing. Prefer this over grep: " +
                "it resolves the name the compiler sees, not same-named text.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    stringProperty(
                        "symbol",
                        "Symbol Path of the Symbol the name is used in, as kt_read_symbol takes it, e.g. " +
                            "'OrderController/create'.",
                    )
                    stringProperty(
                        "name",
                        "The identifier to resolve, e.g. 'place'. It must occur exactly once in the Symbol's span as a " +
                            "whole identifier, unless 'at' is given.",
                    )
                    stringProperty(
                        "at",
                        "Optional snippet of the Symbol's source, exactly as written ($LINE_BREAK_RULE) and occurring " +
                            "exactly once in it, " +
                            "e.g. 'service.place(cmd)'; 'name' is looked up inside it.",
                    )
                    stringProperty(
                        "file",
                        "Optional path to the Symbol's .kt/.kts file, absolute or relative to the project root. Without " +
                            "it the Symbol Path is resolved across the project.",
                    )
                },
                required = listOf("symbol", "name"),
            ),
        ) { getDefinition(it) }

        server.register(
            name = "kt_edit",
            description = "Changes Kotlin code by Symbol, validated against the compiler before anything is written. " +
                "A 'replace' Operation swaps a Symbol's whole span (exactly what kt_read_symbol returns: KDoc or " +
                "comments above, annotations, modifiers, declaration, trailing list comma, end-of-line comment) for " +
                "your code, so a constructor property's new code carries its comma; omitted KDoc or annotations are " +
                "deleted. The code may rename the declaration or hold several. " +
                "'insert_before' and 'insert_after' add your code as a sibling of the Symbol, one blank line from it " +
                "('insert_before' goes above its KDoc and annotations), so they don't add a KDoc, comment or " +
                "annotation to the Symbol itself. For that, use 'edit_in' on the Symbol, with 'old' one whole line of " +
                "its declaration, indentation included: for an annotation, the line with its name; for a KDoc or " +
                "comment, its first annotation's line if it has one (a KDoc below annotations is not its doc), else " +
                "the line with its name. 'new' is your lines, indented alike, followed by that same line; they stay " +
                "directly above it, in the Symbol's span. 'delete' removes the whole span and one " +
                "blank line next to it. Code is re-indented to the target's column, so indentation needn't match. " +
                "'edit_in' replaces 'old', which must occur exactly once in the Symbol's span (whitespace included, " +
                "but $LINE_BREAK_RULE; no regex or fuzzy matching) and must not start inside a " +
                "line's indentation, with 'new' as written (only its line breaks become the file's); it changes one line of a large Symbol cheaply. " +
                "With a file and no symbol, 'edit_in' works on the whole file: the only way to change text outside " +
                "any Symbol, such as the package line, file annotations or imports. " +
                "'create' makes a new .kt/.kts file, 'file', with 'code' as its whole text (only its line breaks become " +
                "'\\n'), validated and written with the rest of the Edit, its missing directories created then; use it " +
                "instead of Write for a new Kotlin file. It fails if the file exists. " +
                "Any Operation may list 'imports' its code needs: each missing one is added to the target's file, " +
                "next to the one sharing most of its package, else in its group of IntelliJ's default layout (or in a " +
                "new block after the package line); none is reordered, and " +
                "nothing is imported unless asked. The Edit removes the imports it made unused (used before it, " +
                "unused after it, in the files it changed) unless an Operation listed them, and lists them in the " +
                "result; imports that were already unused are never touched. An import it couldn't check (no " +
                "answer from the code intelligence) is kept and listed apart. " +
                "Files are written only if the Edit adds no compiler errors to them; otherwise nothing is written and " +
                "the new errors are listed. " +
                "If the Edit changed the header of an existing Symbol (removed or renamed it, or changed its kind, " +
                "receiver, parameters or return type), or added a direct subtype of an existing sealed type, the other files " +
                "that used that Symbol or sealed type are checked too, and the errors the Edit " +
                "introduced there are listed as Collateral Errors. Collateral Errors never block the Edit: it is written, " +
                "so fix them with another Edit. " +
                "An Edit is an ordered batch of Operations, possibly across files: each resolves against the code the " +
                "earlier ones left (so you can rename a Symbol, then target its new name), the batch is validated once " +
                "after the last, and either every file is written or none is (a write failing part-way is reported). " +
                "Intermediate states may not compile. A Symbol Path without a file is looked up on disk, so to " +
                "target a Symbol an earlier Operation added, renamed or created a file for, pass its file. The result lists each written file. " +
                "To write a test before the code it calls, put a stub of each missing declaration (a function " +
                "with body TODO(), an empty class) in the same Edit as the test: the Edit passes Validation and the " +
                "test fails when run. " +
                "A rejected Edit gets an id and is kept until the next kt_edit or kt_rename_symbol call: to fix it, " +
                "call kt_edit with 'amend' set to that id and only the fixing Operations (e.g. an 'edit_in' on the " +
                "whole file adding a missing import). They are appended after its Operations, each resolving against " +
                "the code the earlier ones left, and the combined Edit is validated and written as one, or rejected " +
                "with a new id you can amend in turn. If an amend fails on one of its own Operations, while kotlin-lsp " +
                "is indexing, or names another id, the rejected Edit is still kept under its id: amend it again " +
                "with the corrected fix. An amend can't change or drop the rejected Edit's Operations, and is " +
                "refused if a file it touched changed on disk since or one of its Operations no longer applies; " +
                "then resend the whole batch. " +
                "Prefer this over Edit and other text edits of Kotlin files: nothing is written that fails Validation.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    putJsonObject("operations") {
                        put("type", "array")
                        put("description", "The Operations of this Edit, applied in order; at least one.")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("type") {
                                    put("type", "string")
                                    putJsonArray("enum") { operationTypes.forEach { add(it.name) } }
                                    put("description", "The Operation.")
                                }
                                stringProperty(
                                    "symbol",
                                    "Symbol Path of the target, as kt_read_symbol takes it, e.g. 'OrderController/create'. " +
                                        "Required, except by an 'edit_in' on a whole file; not used by 'create'.",
                                )
                                stringProperty(
                                    "file",
                                    "Optional path to the target's .kt/.kts file, absolute or relative to the project " +
                                        "root. Without it the Symbol Path is resolved across the project. Required by 'create': the new file.",
                                )
                                stringProperty(
                                    "code",
                                    "For 'replace', the complete new source for the Symbol's whole span; for " +
                                        "'insert_before' and 'insert_after', the code to add; for 'create', the new file's whole text. Not used " +
                                        "by 'delete' or 'edit_in'.",
                                )
                                stringProperty(
                                    "old",
                                    "For 'edit_in', the exact text to replace, copied from kt_read_symbol output; it " +
                                        "must occur exactly once in the target.",
                                )
                                stringProperty("new", "For 'edit_in', the text to put in place of 'old', as written; may be empty.")
                                putJsonObject("imports") {
                                    put("type", "array")
                                    putJsonObject("items") { put("type", "string") }
                                    put(
                                        "description",
                                        "Optional imports the code needs, e.g. 'kotlin.time.Duration', 'a.b.*' or 'a.b.C as D', " +
                                            "added to the target's file if missing and validated with the Edit.",
                                    )
                                }
                            }
                            putJsonArray("required") {
                                add("type")
                            }
                        }
                    }
                    stringProperty(
                        "amend",
                        "Optional id of the last rejected Edit, e.g. 'e1', as its result gives it. 'operations' are " +
                            "then appended to its Operations, and the combined Edit is validated and written as one.",
                    )
                },
                required = listOf("operations"),
            ),
        ) { edit(it) }

        server.register(
            name = "kt_rename_symbol",
            description = "Renames a Kotlin Symbol and every Usage of it, across files, modules, main and test " +
                "source, through the language server, so Usages a text search would miss follow too. The changes " +
                "are validated against the compiler before anything is written: if they add compiler errors to any " +
                "changed file, nothing is written and the errors are listed; otherwise every changed file is written, " +
                "or none is. A class named like its file also renames the file (refused if the new file exists). " +
                "The rename removes the imports it made unused (used before it, unused after it); imports that were " +
                "already unused are never touched. The result lists each changed file with its Usages and imports, " +
                "any file rename, the imports removed, those kept because it couldn't check them, and the " +
                "Symbol's new canonical path and the file it is in. A rename is its own call, never an " +
                "Operation of a kt_edit batch. Prefer it over kt_edit or text edits to rename anything.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    stringProperty(
                        "symbol",
                        "Symbol Path of the Symbol to rename, as kt_read_symbol takes it, e.g. 'OrderController/create' or " +
                            "'com.example.a/Config'.",
                    )
                    stringProperty("newName", "The new name: a Kotlin identifier, e.g. 'placeOrder', or a name in backticks.")
                    stringProperty(
                        "file",
                        "Optional path to the Symbol's .kt/.kts file, absolute or relative to the project root. Without it " +
                            "the Symbol Path is resolved across the project.",
                    )
                },
                required = listOf("symbol", "newName"),
            ),
        ) { renameSymbol(it) }
    }

    private suspend fun edit(args: JsonObject): CallToolResult {
        val operations = args["operations"] as? JsonArray ?: return error("Missing required argument: operations (an array)")
        if (operations.isEmpty()) return error("kt_edit needs at least one Operation. Nothing written.")
        // Each Operation with its type as the agent named it, for the messages of its failures.
        val parsed = try {
            operations.mapIndexed(::parseOperation)
        } catch (refused: Refused) {
            return error(refused.message)
        }
        val amend = when (val raw = args["amend"]) {
            null, JsonNull -> null
            else -> (raw as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }
                ?: return error("'amend' must be the id of a rejected Edit, e.g. \"e1\". Nothing written.")
        }
        return Replies.edit(edit(parsed.map { it.second }, amend), parsed.map { it.first }, amend).toResult()
    }

    private suspend fun renameSymbol(args: JsonObject): CallToolResult {
        val symbol = args.string("symbol")?.takeIf { it.isNotBlank() } ?: return error("Missing required argument: symbol")
        val newName = args.string("newName") ?: return error("Missing required argument: newName")
        val file = args.string("file")?.takeIf { it.isNotBlank() }
        return Replies.rename(renameSymbol(symbol, newName, file), symbol, newName).toResult()
    }

    private suspend fun readStructure(args: JsonObject): CallToolResult {
        val path = args.string("file") ?: return error("Missing required argument: file")
        return Replies.readStructure(readStructure(path)).toResult()
    }

    private suspend fun findSymbol(args: JsonObject): CallToolResult {
        val query = args.string("query") ?: return error("Missing required argument: query")
        val kind = args.string("symbolType")?.let { name ->
            SymbolKind.entries.find { it.name == name.uppercase() }
                ?: return error("Unknown symbolType '$name'. Use one of: ${SymbolKind.entries.joinToString()}")
        }
        val limit = args.int("limit") ?: FindSymbol.DEFAULT_LIMIT
        return Replies.findSymbol(findSymbol(query, kind, limit)).toResult()
    }

    private suspend fun readSymbol(args: JsonObject): CallToolResult {
        val path = args.string("file")?.takeIf { it.isNotBlank() }
        val symbol = args.string("symbol") ?: return error("Missing required argument: symbol")
        return Replies.readSymbol(readSymbol(path, symbol)).toResult()
    }

    private suspend fun findUsages(args: JsonObject): CallToolResult {
        val symbol = args.string("symbol") ?: return error("Missing required argument: symbol")
        val file = args.string("file")?.takeIf { it.isNotBlank() }
        val declaredIn = args.string("declaredIn")?.takeIf { it.isNotBlank() }
        return Replies.findUsages(findUsages(symbol, file, declaredIn)).toResult()
    }

    private suspend fun getDefinition(args: JsonObject): CallToolResult {
        val symbol = args.string("symbol") ?: return error("Missing required argument: symbol")
        val name = args.string("name")?.takeIf { it.isNotBlank() } ?: return error("Missing required argument: name")
        val at = args.string("at")?.takeIf { it.isNotEmpty() }
        val file = args.string("file")?.takeIf { it.isNotBlank() }
        return Replies.getDefinition(getDefinition(symbol, name, at, file), atGiven = at != null).toResult()
    }

    /**
     * Registers the tool [name], whose handler runs [block] on the call's arguments. Infrastructure failures (language server
     * down, timeouts) become tool errors instead of protocol errors, worded by [Replies.thrown]. A [VirtualMachineError] leaves
     * the JVM in no state to go on and still propagates.
     *
     * The handler is built here, at registration, and catches within its own body, so a call loads no class of Kronstadt's
     * before it reaches the catch: one loaded on a tool's first call fails if the install was rebuilt since the server
     * started, and must fail inside the catch to get [Replies.thrown]'s advice (#199).
     */
    private fun Server.register(
        name: String,
        description: String,
        inputSchema: ToolSchema,
        block: suspend (JsonObject) -> CallToolResult,
    ) {
        addTool(name = name, description = description, inputSchema = inputSchema) { request ->
            try {
                block(request.arguments ?: JsonObject(emptyMap()))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Replies.thrown(e).toResult()
            } catch (e: VirtualMachineError) {
                throw e
            } catch (e: Error) {
                Replies.thrown(e).toResult()
            }
        }
    }

    /** [this] as the MCP SDK sends it: `isError` is left out of a result, as it always was. */
    private fun Reply.toResult() = CallToolResult(content = listOf(TextContent(text)), isError = isError.takeIf { it })

    /** A malformed call's Reply, before any use case runs. */
    private fun error(s: String) = Reply.error(s).toResult()

    private fun kotlinx.serialization.json.JsonObjectBuilder.stringProperty(name: String, description: String) {
        putJsonObject(name) {
            put("type", "string")
            put("description", description)
        }
    }

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
}

/**
 * The MCP server instructions: which of the tools [KronstadtTools] registers to use instead of the agent's built-in
 * Read, grep and Edit for Kotlin, and to retry rather than fall back when kotlin-lsp is still indexing.
 */
internal fun serverInstructions(root: Path): String = """
    Kronstadt gives symbol-level access to the Kotlin project at $root. For Kotlin code, use it instead of the built-in tools:
    - Read a file: kt_read_structure for its skeleton, then kt_read_symbol for one declaration, instead of Read.
    - Search: kt_find_symbol to locate declarations and kt_find_usages to find where a Symbol is used, instead of grep.
    - Navigate: kt_get_definition to find where a name used inside a Symbol is declared.
    - Change code: kt_edit (replace, insert_before, insert_after, delete or edit_in by Symbol Path; edit_in on a whole file for text outside any Symbol; create for a new file) and kt_rename_symbol, instead of Edit.
    If a tool fails because kotlin-lsp is still indexing, retry it; don't fall back to the built-in tools.
""".trimIndent()
