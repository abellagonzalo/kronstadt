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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kronstadt.core.application.Create
import kronstadt.core.application.Delete
import kronstadt.core.application.Edit
import kronstadt.core.application.EditIn
import kronstadt.core.application.EditResult
import kronstadt.core.application.EditedFile
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindSymbolResult
import kronstadt.core.application.FindUsages
import kronstadt.core.application.FindUsagesResult
import kronstadt.core.application.GetDefinition
import kronstadt.core.application.GetDefinitionResult
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
            name = "kt_find_usages",
            description = "Lists every Usage of one Kotlin Symbol in the project's main and test source (not overrides or " +
                "implementations), using the language server's index. A summary line comes first; then each file (main " +
                "before test), the canonical Symbol Path containing its Usages (pass it with the file to kt_read_symbol " +
                "or kt_edit; '(file level)' outside any Symbol) and one line per Usage. Imports are only counted. At most " +
                "${FindUsages.MAX_USAGES} Usages, whole files only; the files left out are named, pass one as 'file' to see it. Prefer " +
                "this over grep: it finds real Usages only, not same-named text.",
            inputSchema = ToolSchema(
                properties = buildJsonObject {
                    stringProperty(
                        "symbol",
                        "Symbol Path of the Symbol, as kt_read_symbol takes it, resolved across the project, e.g. " +
                            "'OrderController/create' or 'com.example.a/Config'.",
                    )
                    stringProperty(
                        "file",
                        "Optional .kt/.kts file, absolute or relative to the project root: list only the Usages in it.",
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
                        "Optional snippet of the Symbol's source, exactly as written and occurring exactly once in it, " +
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
                "comments above, annotations, modifiers, declaration, end-of-line comment) for your code; omitted " +
                "KDoc or annotations are deleted. The code may rename the declaration or hold several. " +
                "'insert_before' and 'insert_after' add your code as a sibling of the Symbol, one blank line from it " +
                "('insert_before' goes above its KDoc and annotations). 'delete' removes the whole span and one " +
                "blank line next to it. Code is re-indented to the target's column, so indentation needn't match. " +
                "'edit_in' replaces 'old', which must occur exactly once in the Symbol's span (whitespace included, " +
                "no regex or fuzzy matching), with 'new' as written (only its line breaks become the file's); it " +
                "changes one line of a large Symbol cheaply. " +
                "With a file and no symbol, 'edit_in' works on the whole file: the only way to change text outside " +
                "any Symbol, such as the package line, file annotations or imports. " +
                "'create' makes a new .kt/.kts file, 'file', with 'code' as its whole text (only its line breaks become " +
                "'\\n'), validated and written with the rest of the Edit, its missing directories created then; use it " +
                "instead of Write for a new Kotlin file. It fails if the file exists. " +
                "Any Operation may list 'imports' its code needs: each missing one is added to the target's file, " +
                "sorted in among the others (or in a new block after the package line); none is reordered, and " +
                "nothing is imported unless asked. The Edit removes the imports it made unused (used before it, " +
                "unused after it, in the files it changed) unless an Operation listed them, and lists them in the " +
                "result; imports that were already unused are never touched. " +
                "Files are written only if the Edit adds no compiler errors to them; otherwise nothing is written and " +
                "the new errors are listed. " +
                "An Edit is an ordered batch of Operations, possibly across files: each resolves against the code the " +
                "earlier ones left (so you can rename a Symbol, then target its new name), the batch is validated once " +
                "after the last, and either every file is written or none is (a write failing part-way is reported). " +
                "Intermediate states may not compile. A Symbol Path without a file is looked up on disk, so to " +
                "target a Symbol an earlier Operation added, renamed or created a file for, pass its file. The result lists each written file. Prefer this over Edit and other text " +
                "edits of Kotlin files: nothing is written that fails Validation.",
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
                                    putJsonArray("enum") {
                                        add("replace")
                                        add("insert_before")
                                        add("insert_after")
                                        add("delete")
                                        add("edit_in")
                                        add("create")
                                    }
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
                "The result lists each changed file with its Usages and imports, any file rename, any unused imports the " +
                "language server removed, and the Symbol's new canonical path. A rename is its own call, never an " +
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
        val parsed = operations.mapIndexed { index, element ->
            val operation = element as? JsonObject ?: return error("Operation $index must be an object. Nothing written.")
            val type = operation.string("type") ?: return error("Operation $index: missing 'type'. Nothing written.")
            val symbol = operation.string("symbol")?.takeIf { it.isNotBlank() }
            val file = operation.string("file")?.takeIf { it.isNotBlank() }
            fun missing(what: String) = error("Operation $index: missing $what, which '$type' needs. Nothing written.")
            val code = operation.string("code")
            val notStrings = error("Operation $index: 'imports' must be an array of strings. Nothing written.")
            val imports = when (val raw = operation["imports"]) {
                null, JsonNull -> emptyList()
                is JsonArray -> raw.map { element -> (element as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return notStrings }
                else -> return notStrings
            }
            when (type) {
                "replace" -> Replace(file, symbol ?: return missing("'symbol'"), code ?: return missing("'code'"), imports)
                "insert_before" -> InsertBefore(file, symbol ?: return missing("'symbol'"), code ?: return missing("'code'"), imports)
                "insert_after" -> InsertAfter(file, symbol ?: return missing("'symbol'"), code ?: return missing("'code'"), imports)
                "delete" -> Delete(file, symbol ?: return missing("'symbol'"), imports)
                "edit_in" -> {
                    if (symbol == null && file == null) return missing("a 'symbol', a 'file', or both")
                    val old = operation.string("old")?.takeIf { it.isNotEmpty() } ?: return missing("a non-empty 'old'")
                    EditIn(file, symbol, old, operation.string("new") ?: return missing("'new'"), imports)
                }
                "create" -> {
                    if (symbol != null) return error("Operation $index: 'create' takes no 'symbol', only the new 'file' and its 'code'. Nothing written.")
                    Create(file ?: return missing("'file'"), code ?: return missing("'code'"), imports)
                }
                else -> return error(
                    "Operation $index: unsupported type '$type'; use replace, insert_before, insert_after, delete, edit_in or create, and " +
                        "kt_rename_symbol for renames, on its own. Nothing written.",
                )
            }
        }
        return when (val result = edit(parsed)) {
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
            is EditResult.PartiallyWritten -> error(
                "Write-back failed part-way: writing ${result.failed} failed (${result.reason}). " +
                    "Already written, not rolled back: ${result.written.joinToString()}. " +
                    "Nothing else was written; re-read these files before retrying.",
            )
            EditResult.ValidationTimedOut -> error("validation timed out; nothing written")
            is EditResult.NotReady -> notReady(result.readiness)
            is EditResult.OperationFailed -> error("Operation ${result.index}: ${failure(result.failure)} Nothing written.")
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
            is RenameResult.TargetExists -> error(
                "The rename would rename ${result.file} to ${result.target}, which already exists; nothing written. " +
                    "Pick another name, or move that file first.",
            )
            is RenameResult.Rejected -> error(
                buildString {
                    val count = result.newErrors.size
                    append("Rename rejected: $count new error${if (count == 1) "" else "s"}; nothing written.\n")
                    result.newErrors.forEach { append(problem(it, withFile = true)) }
                    append("fix what makes the new name clash and retry")
                },
            )
            is RenameResult.ChangedOnDisk -> error("${result.file} changed on disk since read; re-read and retry")
            is RenameResult.PartiallyWritten -> error(
                "Write-back failed part-way: writing ${result.failed} failed (${result.reason}). " +
                    "Already written, not rolled back: ${result.written.joinToString()}. " +
                    "Nothing else was written; re-read these files before retrying.",
            )
            RenameResult.ValidationTimedOut -> error("validation timed out; nothing written")
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
            if (file.preexistingErrors > 0) {
                append("    ${file.preexistingErrors} error${if (file.preexistingErrors == 1) " was" else "s were"} already in the file.\n")
            }
        }
        result.fileRenamed?.let { append("File renamed: ").append(it.from).append(" -> ").append(it.to).append('\n') }
        if (result.importsRemoved.isNotEmpty()) {
            append("Unused imports kotlin-lsp removed:\n")
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
        is OperationFailure.LookupFailed -> sentence(describe(failure.failure, "kt_edit"))
        is OperationFailure.RemovedEarlier ->
            "'${failure.symbol}' in ${failure.file} was removed or renamed by an earlier Operation of this Edit; target " +
                "what that Operation left." +
                (if (failure.suggestions.isEmpty()) "" else " Close names:\n" + candidates(failure.suggestions, withFiles = false) + "\n")
        is OperationFailure.NeedsFile ->
            "'${failure.symbol}' exists only in what earlier Operations of this Edit wrote, and a Symbol Path without a " +
                "file is looked up on disk; pass its file:\n" + candidates(failure.candidates, withFiles = true) + "\n"
        is OperationFailure.NotExactlyOnce -> {
            val where = (failure.symbol?.let { "$it in " } ?: "") + failure.file
            if (failure.lines.isEmpty()) {
                "'old' doesn't occur in $where, exactly as written (whitespace included). " +
                    (if (failure.symbol == null) "Read the file to copy it." else "Use kt_read_symbol to see its source.")
            } else {
                "'old' occurs ${failure.count} times in $where; make it longer so it occurs exactly once:\n" +
                    failure.lines.joinToString("\n") { "  L${it.line}: ${it.source}" } + "\n"
            }
        }
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
    }

    private suspend fun readStructure(args: JsonObject): CallToolResult {
        val path = args.string("filePath") ?: return error("Missing required argument: filePath")
        val xml = args.boolean("formatOutputAsXml") ?: true
        return when (val result = readStructure(path, xml)) {
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
        return when (val result = findUsages(symbol, file)) {
            is FindUsagesResult.Found -> text(result.text)
            is FindUsagesResult.NoUsages -> text(
                "No usages of ${result.symbol}" +
                    (if (result.imports > 0) " other than ${result.imports} import${if (result.imports == 1) "" else "s"}" else "") +
                    " (searched ${result.searchedFiles} file${if (result.searchedFiles == 1) "" else "s"})",
            )
            is FindUsagesResult.LookupFailed -> error(describe(result.failure, "kt_find_usages"))
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
                error(
                    if (result.lines.isEmpty()) {
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

    /** Infrastructure failures (language server down, timeouts) become tool errors instead of protocol errors. */
    private suspend fun handle(request: CallToolRequest, block: suspend (JsonObject) -> CallToolResult): CallToolResult =
        try {
            block(request.arguments ?: JsonObject(emptyMap()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error("${e::class.simpleName}: ${e.message}")
        }

    private fun notReady(readiness: Readiness.NotReady) = error(
        "kotlin-lsp still indexing (phase ${readiness.phase}, ${readiness.elapsed.inWholeSeconds} s elapsed); nothing returned/written, retry",
    )

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
