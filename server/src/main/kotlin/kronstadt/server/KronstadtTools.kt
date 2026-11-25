package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
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
import kronstadt.core.application.CollateralErrors
import kronstadt.core.application.Create
import kronstadt.core.application.Delete
import kronstadt.core.application.Edit
import kronstadt.core.application.EditIn
import kronstadt.core.application.EditInScope
import kronstadt.core.application.EditResult
import kronstadt.core.application.EditedFile
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindSymbolResult
import kronstadt.core.application.FindUsages
import kronstadt.core.application.FindUsagesResult
import kronstadt.core.application.GetDefinition
import kronstadt.core.application.GetDefinitionResult
import kronstadt.core.application.IndentationHint
import kronstadt.core.application.InsertAfter
import kronstadt.core.application.InsertBefore
import kronstadt.core.application.OperationFailure
import kronstadt.core.application.Problem
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadStructureResult
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.ReadSymbolResult
import kronstadt.core.application.RenameResult
import kronstadt.core.application.RenameSymbol
import kronstadt.core.application.Replace
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolKind

/**
 * How a snippet ('old', 'at') matches line endings, as the tools state it (ADR 0004): a "line break" in the snippet,
 * a "line ending" in the file.
 */
private const val ANY_LINE_BREAK = "any line break matches any line ending"

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
        server.addTool(
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
        ) { request -> handle(request) { readSymbol(it) } }

        server.addTool(
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
        ) { request -> handle(request) { findUsages(it) } }

        server.addTool(
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
                        "Optional snippet of the Symbol's source, exactly as written ($ANY_LINE_BREAK) and occurring " +
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
        ) { request -> handle(request) { getDefinition(it) } }

        server.addTool(
            name = "kt_edit",
            description = "Changes Kotlin code by Symbol, validated against the compiler before anything is written. " +
                "A 'replace' Operation swaps a Symbol's whole span (exactly what kt_read_symbol returns: KDoc or " +
                "comments above, annotations, modifiers, declaration, trailing list comma, end-of-line comment) for " +
                "your code, so a constructor property's new code carries its comma; omitted KDoc or annotations are " +
                "deleted. The code may rename the declaration or hold several. " +
                "'insert_before' and 'insert_after' add your code as a sibling of the Symbol, one blank line from it " +
                "('insert_before' goes above its KDoc and annotations). 'delete' removes the whole span and one " +
                "blank line next to it. Code is re-indented to the target's column, so indentation needn't match. " +
                "'edit_in' replaces 'old', which must occur exactly once in the Symbol's span (whitespace included, " +
                "but $ANY_LINE_BREAK; no regex or fuzzy matching) and must not start inside a " +
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
                "result; imports that were already unused are never touched. " +
                "Files are written only if the Edit adds no compiler errors to them; otherwise nothing is written and " +
                "the new errors are listed. " +
                "If the Edit changed the header of an existing Symbol (removed or renamed it, or changed its kind, " +
                "receiver, parameters or return type), the other files that used it are checked too, and the errors the Edit " +
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
        ) { request -> handle(request) { edit(it) } }

        server.addTool(
            name = "kt_rename_symbol",
            description = "Renames a Kotlin Symbol and every Usage of it, across files, modules, main and test " +
                "source, through the language server, so Usages a text search would miss follow too. The changes " +
                "are validated against the compiler before anything is written: if they add compiler errors to any " +
                "changed file, nothing is written and the errors are listed; otherwise every changed file is written, " +
                "or none is. A class named like its file also renames the file (refused if the new file exists). " +
                "The rename removes the imports it made unused (used before it, unused after it); imports that were " +
                "already unused are never touched. The result lists each changed file with its Usages and imports, " +
                "any file rename, the imports removed, and the Symbol's new canonical path. A rename is its own call, never an " +
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
        ) { request -> handle(request) { renameSymbol(it) } }
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
        return when (val result = edit(parsed.map { it.second }, amend)) {
            is EditResult.Written -> text(
                (result.files.map { written(it) } + collateral(result.collateral)).filter { it.isNotEmpty() }.joinToString("\n"),
            )
            is EditResult.ValidationFailed -> error(describe(result.failure, "Edit", result.id))
            is EditResult.WriteBackFailed -> error(describe(result.failure))
            is EditResult.NotReady ->
                result.kept?.let { error("${notReadyText(result.readiness)}. The rejected Edit $it is still kept: retry with kt_edit amend: \"$it\".") }
                    ?: notReady(result.readiness)
            is EditResult.OperationFailed ->
                if (result.inRejected) {
                    error(
                        "The rejected Edit $amend no longer applies: ${failure(result.failure, "operation")} " +
                            "Nothing written; resend the whole batch.",
                    )
                } else {
                    val kept = result.kept?.let {
                        "; the rejected Edit $it is still kept: amend it again with kt_edit amend: \"$it\" and only the fixing Operations"
                    }.orEmpty()
                    error("Operation ${result.index}: ${failure(result.failure, parsed[result.index].first)} Nothing written$kept.")
                }
            is EditResult.NoRejectedEdit -> error(
                result.kept?.let {
                    "no rejected Edit ${result.id} to amend; the rejected Edit $it is still kept: amend it with kt_edit amend: \"$it\", " +
                        "or resend the whole batch"
                } ?: "no rejected Edit ${result.id} to amend; resend the whole batch",
            )
        }
    }

    private suspend fun renameSymbol(args: JsonObject): CallToolResult {
        val symbol = args.string("symbol")?.takeIf { it.isNotBlank() } ?: return error("Missing required argument: symbol")
        val newName = args.string("newName") ?: return error("Missing required argument: newName")
        val file = args.string("file")?.takeIf { it.isNotBlank() }
        return when (val result = renameSymbol(symbol, newName, file)) {
            is RenameResult.Renamed -> text(renamed(symbol, newName, result))
            is RenameResult.LookupFailed -> error("${sentence(describe(result.failure, "kt_rename_symbol"))} Nothing written.")
            is RenameResult.InvalidName -> error("'${result.name}' is not a Kotlin identifier; pass a plain name, or one in backticks. Nothing written.")
            is RenameResult.CannotRename -> error("Cannot rename ${result.symbol}: ${result.reason}. Nothing written.")
            is RenameResult.ValidationFailed -> error(describe(result.failure, "Rename"))
            is RenameResult.WriteBackFailed -> error(describe(result.failure))
            is RenameResult.NotReady -> notReady(result.readiness)
        }
    }

    /**
     * A summary line with the new path, as kt_find_usages counts (`3 usages in 2 files (+1 import)`), then each changed
     * file with its usages and new warnings, then any file rename and removed imports.
     */
    private fun renamed(symbol: String, newName: String, result: RenameResult.Renamed) = buildString {
        fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"
        val usages = result.files.sumOf { it.usages }
        val imports = result.files.sumOf { it.imports }
        append("Renamed ").append(symbol).append(" to ").append(newName).append(": ")
        append(count(usages, "usage")).append(" in ").append(count(result.files.count { it.usages > 0 }, "file"))
        if (imports > 0) append(" (+").append(count(imports, "import")).append(')')
        append(", and the declaration. New path: ")
        append(result.newPath ?: "unknown (several Symbols match it now; use kt_read_structure)").append('\n')
        result.files.forEachIndexed { index, file ->
            val parts = listOfNotNull(
                "declaration".takeIf { index == 0 },
                file.usages.takeIf { it > 0 }?.let { count(it, "usage") },
                file.imports.takeIf { it > 0 }?.let { count(it, "import") },
            ).ifEmpty { listOf("unused imports removed") }
            append("  ").append(file.file).append(": ").append(parts.joinToString(", ")).append('\n')
            if (file.newWarnings.isNotEmpty()) {
                append("  New warnings:\n")
                file.newWarnings.forEach { append("  ").append(problem(it, withFile = false)) }
                if (file.moreWarnings > 0) append("    (${file.moreWarnings} more)\n")
            }
            append(preexistingErrors(file.preexistingErrors, file.morePreexistingErrors, indent = "  "))
        }
        result.fileRenamed?.let { append("File renamed: ").append(it.from).append(" -> ").append(it.to).append('\n') }
        if (result.importsRemoved.isNotEmpty()) {
            append("Removed imports it made unused:\n")
            result.importsRemoved.forEach { append("  ").append(it.file).append(": ").append(it.import).append('\n') }
        }
    }.trimEnd()

    /** One file's outcome: the Symbols the edit left, the imports it made unused and removed, then new warnings and the errors it already had. */
    private fun written(file: EditedFile) = buildString {
        append(if (file.created) "Created " else "Edited ").append(file.file).append('\n')
        file.symbols.forEach { append("  ").append(it.path).append("  // L").append(it.firstLine).append('-').append(it.lastLine).append('\n') }
        if (file.importsRemoved.isNotEmpty()) {
            append("Removed imports it made unused:\n")
            file.importsRemoved.forEach { append("  ").append(it).append('\n') }
        }
        if (file.newWarnings.isNotEmpty()) {
            append("New warnings:\n")
            file.newWarnings.forEach { append(problem(it, withFile = false)) }
            if (file.moreWarnings > 0) append("  (${file.moreWarnings} more)\n")
        }
        append(preexistingErrors(file.preexistingErrors, file.morePreexistingErrors, indent = ""))
    }.trimEnd()

    /**
     * The [errors] a file already had, as listed (beyond them, [more] are only counted), each line after [indent], then a
     * hint that they may be false; empty if there are none.
     */
    private fun preexistingErrors(errors: List<Problem>, more: Int, indent: String) = buildString {
        if (errors.isEmpty()) return@buildString
        append(indent).append("Errors already in the file:\n")
        errors.forEach { append(indent).append(problem(it, withFile = false)) }
        if (more > 0) append(indent).append("  ($more more)\n")
        append(indent).append("They may be false: kotlin-lsp can lag behind edits made outside Kronstadt. ")
        append("Check with the compiler or tests before acting on them.\n")
    }

    /**
     * The Collateral Errors a written Edit introduced in files it didn't change, the files left unchecked and whether the
     * check finished; empty if there is nothing to say. A failed check is logged too.
     */
    private fun collateral(collateral: CollateralErrors) = buildString {
        if (collateral.errors.isNotEmpty()) {
            val count = collateral.errors.size + collateral.moreErrors
            append("Collateral Errors: $count new error${if (count == 1) "" else "s"} in files this Edit didn't change, ")
            append("which Validation doesn't check. Nothing was blocked: the Edit is written; fix them with another Edit.\n")
            collateral.errors.forEach { append(problem(it, withFile = true)) }
            if (collateral.moreErrors > 0) append("  (${collateral.moreErrors} more)\n")
        }
        if (collateral.filesUnchecked > 0) {
            val files = if (collateral.filesUnchecked == 1) "1 more file" else "${collateral.filesUnchecked} more files"
            append("$files using the Symbols this Edit changed weren't checked for Collateral Errors.\n")
        }
        when (val unfinished = collateral.unfinished) {
            null -> Unit
            CollateralErrors.Unfinished.TimedOut ->
                append("The check of other files for Collateral Errors didn't finish in time; they may have some. The Edit is written.\n")
            is CollateralErrors.Unfinished.Failed -> {
                System.err.println("kronstadt: the check for Collateral Errors failed: ${unfinished.reason}")
                append("The check of other files for Collateral Errors failed (${unfinished.reason}); they may have some. The Edit is written.\n")
            }
        }
    }.trimEnd()

    /** Why an Operation of [type], as the agent named it, can't be applied. */
    private fun failure(failure: OperationFailure, type: String): String = when (failure) {
        is OperationFailure.LookupFailed -> sentence(describe(failure.failure, "kt_edit"))
        is OperationFailure.RemovedEarlier ->
            "'${failure.symbol}' in ${failure.file} was removed or renamed by an earlier Operation of this Edit; target " +
                "what that Operation left." +
                (if (failure.suggestions.isEmpty()) "" else " Close names:\n" + candidates(failure.suggestions, withFiles = false) + "\n")
        is OperationFailure.RemovedEarlierInFiles -> {
            val files = failure.removals.map { it.file }
            val suggestions = failure.removals.flatMap { it.suggestions }
            "'${failure.symbol}' was removed or renamed by earlier Operations of this Edit in " +
                files.dropLast(1).joinToString(", ") + " and " + files.last() + "; target what those Operations left." +
                (if (suggestions.isEmpty()) "" else " Close names:\n" + candidates(suggestions, withFiles = true) + "\n")
        }
        is OperationFailure.NeedsFile ->
            "'${failure.symbol}' exists only in what earlier Operations of this Edit wrote, and a Symbol Path without a " +
                "file is looked up on disk; pass its file:\n" + candidates(failure.candidates, withFiles = true) + "\n"
        is OperationFailure.NotExactlyOnce -> {
            val hint = failure.indentation
            // Missing from a Symbol a bare name chose over same-named functions: say so, as 'old' may be in one of them.
            val preferred = failure.scope.preferred
            val missingIn = where(failure.scope, preferred?.let { " (${it.picked})" } ?: "")
            val sharedName = preferred?.let { " " + it.sharedName.replaceFirstChar(Char::uppercaseChar) + "." } ?: ""
            if (failure.lines.isEmpty() && hint != null) {
                "'old' doesn't occur in $missingIn exactly as written, but " + indentationHint(hint, "'old'") + " " +
                    (if (failure.scope.wholeFile) "Read the file to copy it." else "Copy 'old' from kt_read_symbol output.") +
                    sharedName
            } else if (failure.lines.isEmpty()) {
                "'old' doesn't occur in $missingIn, exactly as written (whitespace included; $ANY_LINE_BREAK). " +
                    (if (failure.scope.wholeFile) "Read the file to copy it." else "Use kt_read_symbol to see its source.") +
                    sharedName
            } else {
                "'old' occurs ${failure.count} times in ${where(failure.scope)}; make it longer so it occurs exactly once:\n" +
                    failure.lines.joinToString("\n") { "  L${it.line}: ${it.source}" } + "\n"
            }
        }
        is OperationFailure.StartsInIndentation ->
            "'old' starts inside line ${failure.line}'s indentation in ${where(failure.scope)}; start it at the beginning of " +
                "the line or after the indentation."
        is OperationFailure.FileExists ->
            if (failure.inEdit) {
                "An earlier Operation of this Edit already created or changed ${failure.file}; 'create' makes a file that " +
                    "doesn't exist yet, once."
            } else {
                "${failure.file} already exists; change it with the other Operations, or edit_in on the whole file."
            }
        is OperationFailure.InvalidImport ->
            "'${failure.import}' is not an import directive; give a dotted name, optionally ending in '.*' or 'as Alias', " +
                "e.g. 'kotlin.time.Duration'."
        OperationFailure.BlankCode -> "'$type' needs code with at least one non-blank line."
    }

    /**
     * An `edit_in`'s [scope] as the agent named it: the Symbol and its file, or just the file. [kind], e.g. ` (the property)`,
     * follows the Symbol.
     */
    private fun where(scope: EditInScope, kind: String = "") = (scope.symbol?.let { "$it$kind in " } ?: "") + scope.file

    /**
     * Where a snippet, [named] as the agent passed it (`'old'`), occurs with indentation ignored, and how it differs: a
     * sentence's end. The indents name their line when it isn't the one the snippet starts on.
     */
    private fun indentationHint(hint: IndentationHint, named: String): String {
        val indents = hint.indents ?: return "occurs once at line ${hint.line} if indentation is ignored, where its lines are " +
            "indented differently from the file's."
        val indented = if (indents.line == hint.line) "it" else "line ${indents.line}"
        return "occurs once at line ${hint.line} if indentation is ignored: the file indents $indented by ${indent(indents.file)}, " +
            "$named by ${indent(indents.snippet)}."
    }

    /** Leading whitespace in words: `4 spaces`, `1 tab`, `1 tab and 2 spaces`, `nothing`. */
    private fun indent(whitespace: String): String {
        fun count(n: Int, unit: String) = if (n == 0) null else "$n $unit" + if (n == 1) "" else "s"
        val tabs = whitespace.count { it == '\t' }
        return listOfNotNull(count(tabs, "tab"), count(whitespace.length - tabs, "space")).joinToString(" and ").ifEmpty { "nothing" }
    }

    private suspend fun readStructure(args: JsonObject): CallToolResult {
        val path = args.string("file") ?: return error("Missing required argument: file")
        return when (val result = readStructure(path)) {
            is ReadStructureResult.Structure -> text(result.text)
            is ReadStructureResult.NoDeclarations -> text("No declarations found in ${result.path}.")
            is ReadStructureResult.LookupFailed -> error(describe(result.failure, "kt_read_structure"))
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
                    if (result.mayBeIncomplete) {
                        append("($INDEX_STOPS, so this list may be incomplete; narrow the query)\n")
                    }
                }.trimEnd(),
            )
            is FindSymbolResult.NoMatches -> text(
                "No symbols matching '${result.query}'" + (result.kind?.let { " of kind ${it.name}" } ?: "") + "." +
                    if (result.mayBeIncomplete) {
                        " ${INDEX_STOPS.replaceFirstChar { it.uppercase() }}, so some may have been left out; narrow the query."
                    } else {
                        ""
                    },
            )
            is FindSymbolResult.InvalidQuery -> error(result.reason)
            is FindSymbolResult.NotReady -> notReady(result.readiness)
        }
    }

    private suspend fun readSymbol(args: JsonObject): CallToolResult {
        val path = args.string("file")?.takeIf { it.isNotBlank() }
        val symbol = args.string("symbol") ?: return error("Missing required argument: symbol")
        return when (val result = readSymbol(path, symbol)) {
            is ReadSymbolResult.Found -> text(result.text)
            is ReadSymbolResult.LookupFailed -> error(describe(result.failure, "kt_read_symbol"))
            is ReadSymbolResult.NotReady -> notReady(result.readiness)
        }
    }

    private suspend fun findUsages(args: JsonObject): CallToolResult {
        val symbol = args.string("symbol") ?: return error("Missing required argument: symbol")
        val file = args.string("file")?.takeIf { it.isNotBlank() }
        val declaredIn = args.string("declaredIn")?.takeIf { it.isNotBlank() }
        return when (val result = findUsages(symbol, file, declaredIn)) {
            is FindUsagesResult.Found -> text(result.text)
            is FindUsagesResult.NoUsages -> text(
                "No usages of ${result.symbol}" +
                    (if (result.imports > 0) " other than ${result.imports} import${if (result.imports == 1) "" else "s"}" else "") +
                    " (searched ${result.searchedFiles} file${if (result.searchedFiles == 1) "" else "s"})",
            )
            is FindUsagesResult.LookupFailed -> error(
                describe(
                    result.failure,
                    "kt_find_usages",
                    when (result.argument) {
                        FindUsagesResult.FileArgument.DECLARED_IN -> "declaredIn"
                        FindUsagesResult.FileArgument.FILE -> "file"
                        null -> null
                    },
                ),
            )
            is FindUsagesResult.NotReady -> notReady(result.readiness)
        }
    }

    private suspend fun getDefinition(args: JsonObject): CallToolResult {
        val symbol = args.string("symbol") ?: return error("Missing required argument: symbol")
        val name = args.string("name")?.takeIf { it.isNotBlank() } ?: return error("Missing required argument: name")
        val at = args.string("at")?.takeIf { it.isNotEmpty() }
        val file = args.string("file")?.takeIf { it.isNotBlank() }
        return when (val result = getDefinition(symbol, name, at, file)) {
            is GetDefinitionResult.Found -> text(result.text)
            is GetDefinitionResult.Unresolved -> text(
                "'${result.name}' on L${result.line} of ${result.symbol} resolves to no declaration (a keyword, a literal " +
                    "or an unresolved reference)." + (result.diagnostic?.let { " Compiler: $it" } ?: ""),
            )
            is GetDefinitionResult.NotExactlyOnce -> {
                val what = "'${result.text}'" + if (result.text == name) " (as a whole identifier)" else ""
                val where = "${result.symbol} in ${result.file}"
                val hint = result.indentation
                error(
                    if (result.lines.isEmpty() && hint != null) {
                        "$what doesn't occur in $where exactly as written, but " +
                            indentationHint(hint, "'at'") + " Copy 'at' from kt_read_symbol output."
                    } else if (result.lines.isEmpty()) {
                        "$what doesn't occur in $where. Use kt_read_symbol to see its source."
                    } else {
                        "$what occurs more than once in $where; " +
                            (if (at != null && result.text == name) "pick an 'at' snippet in which it occurs once" else "pass an 'at' snippet that occurs exactly once") +
                            ":\n" +
                            result.lines.joinToString("\n") { "  L${it.line}: ${it.source}" }
                    },
                )
            }
            is GetDefinitionResult.LookupFailed -> error(describe(result.failure, "kt_get_definition"))
            is GetDefinitionResult.NotReady -> notReady(result.readiness)
        }
    }

    /**
     * Infrastructure failures (language server down, timeouts) become tool errors instead of protocol errors. An
     * [AgentFacingFailure] shows its message alone; any other exception also names its class, since its message may
     * be empty or unclear on its own.
     */
    private suspend fun handle(request: CallToolRequest, block: suspend (JsonObject) -> CallToolResult): CallToolResult =
        try {
            block(request.arguments ?: JsonObject(emptyMap()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: AgentFacingFailure) {
            error(e.message)
        } catch (e: Exception) {
            error("${e::class.simpleName}: ${e.message}")
        }

    private fun notReady(readiness: Readiness.NotReady) = error(notReadyText(readiness))

    private fun notReadyText(readiness: Readiness.NotReady) =
        "kotlin-lsp still indexing (phase ${readiness.phase}, ${readiness.elapsed.inWholeSeconds} s elapsed); nothing returned/written, retry"

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))

    private fun error(s: String) = CallToolResult(content = listOf(TextContent(s)), isError = true)

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
