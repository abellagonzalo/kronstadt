package kronstadt.server

import kronstadt.core.application.CollateralErrors
import kronstadt.core.application.EditInScope
import kronstadt.core.application.EditResult
import kronstadt.core.application.EditedFile
import kronstadt.core.application.FindSymbolResult
import kronstadt.core.application.FindUsagesResult
import kronstadt.core.application.FoundDefinition
import kronstadt.core.application.GetDefinitionResult
import kronstadt.core.application.IndentationHint
import kronstadt.core.application.LookupFailure
import kronstadt.core.application.OperationFailure
import kronstadt.core.application.Problem
import kronstadt.core.application.ReadStructureResult
import kronstadt.core.application.ReadSymbolResult
import kronstadt.core.application.RenameResult
import kronstadt.core.application.SourceLine
import kronstadt.core.application.ValidationFailure
import kronstadt.core.application.WriteBackFailure
import kronstadt.core.domain.PreferredOverFunctions
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolCandidate
import kronstadt.core.domain.SymbolKind

/** A Reply: what Kronstadt tells the agent in answer to one tool call, a result or an error ([isError]), as [text]. */
internal data class Reply(val isError: Boolean, val text: String) {
    companion object {
        /** A Reply that answers the call. */
        fun result(text: String) = Reply(isError = false, text)

        /** A Reply that says why the call failed. */
        fun error(text: String) = Reply(isError = true, text)
    }
}

/**
 * What a tool advises when a Symbol Path or file names no one Symbol, where tools differ: the [tool]'s name, which
 * argument takes the file that [declares][declaringFile] a Symbol, and what to suggest for a Symbol that [shares a
 * line][onSharedLine] with other code.
 */
internal enum class ToolAdvice(val tool: String, val declaringFile: DeclaringFile, val onSharedLine: OnSharedLine) {
    READ_STRUCTURE("kt_read_structure", DeclaringFile.FILE, OnSharedLine.READ_ENCLOSING_SYMBOL),
    READ_SYMBOL("kt_read_symbol", DeclaringFile.FILE, OnSharedLine.READ_ENCLOSING_SYMBOL),

    /** Resolved project-wide, kt_find_usages takes a candidate's file as declaredIn, not as file, which only filters Usages. */
    FIND_USAGES("kt_find_usages", DeclaringFile.DECLARED_IN, OnSharedLine.READ_ENCLOSING_SYMBOL),
    GET_DEFINITION("kt_get_definition", DeclaringFile.FILE, OnSharedLine.READ_ENCLOSING_SYMBOL),

    /** kt_edit doesn't advise replacing the enclosing Symbol: that costs more, and it may share a line too (#99). */
    EDIT("kt_edit", DeclaringFile.FILE, OnSharedLine.EDIT_IN_WHOLE_FILE),
    RENAME_SYMBOL("kt_rename_symbol", DeclaringFile.FILE, OnSharedLine.READ_ENCLOSING_SYMBOL),
    ;

    /** The argument that takes the file declaring a Symbol: `file`, or `declaredIn` when `file` means something else. */
    enum class DeclaringFile { FILE, DECLARED_IN }

    /** What to do instead with a Symbol that has no whole-line span. */
    enum class OnSharedLine { READ_ENCLOSING_SYMBOL, EDIT_IN_WHOLE_FILE }
}

/**
 * Builds every Reply the tools give from core's result values, plus the few tool inputs the wording needs. Results core
 * already renders as text pass through unchanged.
 */
internal object Replies {
    fun readStructure(result: ReadStructureResult): Reply = when (result) {
        is ReadStructureResult.Structure -> Reply.result(StructureRenderer.render(result.file))
        is ReadStructureResult.NoDeclarations -> Reply.result("No declarations found in ${result.path}.")
        is ReadStructureResult.LookupFailed -> Reply.error(lookupFailure(result.failure, ToolAdvice.READ_STRUCTURE))
    }

    fun findSymbol(result: FindSymbolResult): Reply = when (result) {
        is FindSymbolResult.Found -> Reply.result(
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
        is FindSymbolResult.NoMatches -> Reply.result(
            "No symbols matching '${result.query}'" + (result.kind?.let { " of kind ${it.name}" } ?: "") + "." +
                if (result.mayBeIncomplete) {
                    " ${INDEX_STOPS.replaceFirstChar { it.uppercase() }}, so some may have been left out; narrow the query."
                } else {
                    ""
                },
        )
        // kind is filtered after the search, so it wouldn't make it faster: only a more specific name does.
        is FindSymbolResult.TimedOut -> Reply.error(
            "Searching for '${result.query}' timed out: it is too common a name for kotlin-lsp to search in time; " +
                "narrow the query with a longer, more specific name",
        )
        is FindSymbolResult.InvalidQuery -> Reply.error(result.reason)
        is FindSymbolResult.NotReady -> notReady(result.readiness)
    }

    fun readSymbol(result: ReadSymbolResult): Reply = when (result) {
        is ReadSymbolResult.Found -> {
            val span = result.span
            val note = result.preferred?.let { " (${picked(it)}; ${sharedName(it)})" } ?: ""
            Reply.result("// ${result.symbol} in ${result.file}, L${span.firstLine + 1}-${span.lastLine + 1}$note\n${span.text}")
        }
        is ReadSymbolResult.LookupFailed -> Reply.error(lookupFailure(result.failure, ToolAdvice.READ_SYMBOL))
        is ReadSymbolResult.NotReady -> notReady(result.readiness)
    }

    fun findUsages(result: FindUsagesResult): Reply = when (result) {
        is FindUsagesResult.Found -> Reply.result(usages(result))
        is FindUsagesResult.NoUsages -> Reply.result(
            "No usages of ${result.symbol}" +
                (if (result.imports > 0) " other than ${counted(result.imports, "import")}" else "") +
                " (searched ${counted(result.searchedFiles, "file")})",
        )
        is FindUsagesResult.LookupFailed -> Reply.error(
            lookupFailure(
                result.failure,
                ToolAdvice.FIND_USAGES,
                when (result.argument) {
                    FindUsagesResult.FileArgument.DECLARED_IN -> "declaredIn"
                    FindUsagesResult.FileArgument.FILE -> "file"
                    null -> null
                },
            ),
        )
        is FindUsagesResult.NotReady -> notReady(result.readiness)
    }

    /** kt_get_definition's Reply; [atGiven] says whether the agent passed an `at` Snippet. */
    fun getDefinition(result: GetDefinitionResult, atGiven: Boolean): Reply = when (result) {
        is GetDefinitionResult.Found -> Reply.result(result.definitions.joinToString("\n\n", transform = ::definition))
        is GetDefinitionResult.Unresolved -> Reply.result(
            "'${result.name}' on L${result.line} of ${result.symbol} resolves to no declaration (a keyword, a literal " +
                "or an unresolved reference)." + (result.diagnostic?.let { " Compiler: $it" } ?: ""),
        )
        is GetDefinitionResult.AtNotExactlyOnce -> {
            val miss = result.miss
            val what = "'${miss.snippet}'"
            val where = "${result.symbol} in ${result.file}"
            val hint = miss.indentation
            Reply.error(
                if (miss.lines.isEmpty() && hint != null) {
                    "$what doesn't occur in $where exactly as written, but " +
                        indentationHint(hint, "'at'") + " Copy 'at' from kt_read_symbol output."
                } else if (miss.lines.isEmpty()) {
                    "$what doesn't occur in $where. Use kt_read_symbol to see its source."
                } else {
                    "$what occurs more than once in $where; pass an 'at' snippet that occurs exactly once:\n" + sourceLines(miss.lines)
                },
            )
        }
        is GetDefinitionResult.NameNotExactlyOnce -> {
            val what = "'${result.name}' (as a whole identifier)"
            val where = "${result.symbol} in ${result.file}"
            Reply.error(
                if (result.lines.isEmpty()) {
                    "$what doesn't occur in $where. Use kt_read_symbol to see its source."
                } else {
                    "$what occurs more than once in $where; " +
                        (if (atGiven) "pick an 'at' snippet in which it occurs once" else "pass an 'at' snippet that occurs exactly once") +
                        ":\n" + sourceLines(result.lines)
                },
            )
        }
        is GetDefinitionResult.LookupFailed -> Reply.error(lookupFailure(result.failure, ToolAdvice.GET_DEFINITION))
        is GetDefinitionResult.NotReady -> notReady(result.readiness)
    }

    /** A summary line, then each file listed, the Symbol Paths in it that hold Usages, and one line per Usage. */
    private fun usages(result: FindUsagesResult.Found): String = buildString {
        append(counted(result.usageCount, "usage")).append(" in ").append(counted(result.fileCount, "file"))
        if (result.importCount > 0) append(" (+").append(counted(result.importCount, "import")).append(')')
        result.files.forEach { inFile ->
            append('\n').append(inFile.file)
            inFile.inSymbols.forEach { inSymbol ->
                append("\n  ").append(inSymbol.symbol ?: FILE_LEVEL)
                inSymbol.lines.forEach { usage -> append("\n    L${usage.line}: ").append(cutLine(usage.source)) }
            }
        }
        if (result.leftOut.isNotEmpty()) {
            append("\n… ").append(counted(result.leftOutUsageCount, "more usage"))
            append(" in ").append(counted(result.leftOut.size, "file")).append(": ")
            append(result.leftOut.joinToString(", "))
        }
    }

    /** A Usage's source line, cut to [MAX_USAGE_LINE] characters with a trailing ellipsis. */
    private fun cutLine(source: String) = if (source.length <= MAX_USAGE_LINE) source else source.take(MAX_USAGE_LINE - 1) + "…"

    /** One definition as its own block: a header line naming it and where it is, then what of it can be shown. */
    private fun definition(found: FoundDefinition): String = when (found) {
        is FoundDefinition.InSource -> {
            val listing = found.listing
            "// ${listing.path} in ${found.file}, L${listing.firstLine}-${listing.lastLine}\n${found.head}\n" +
                "// Body omitted. Full source: ${ToolAdvice.READ_SYMBOL.tool}(symbol=\"${listing.path}\", file=\"${found.file}\")"
        }
        is FoundDefinition.Local ->
            "// ${found.name} is local to ${found.enclosing ?: FILE_LEVEL} in ${found.file}, not addressable; declared at L${found.line}\n" +
                found.source
        is FoundDefinition.InLibrary ->
            "// ${found.qualifiedName} in ${found.origin}!/${found.entry} (library: read-only, not addressable)\n${found.head}"
        is FoundDefinition.LibraryWithoutSources ->
            "// ${found.member?.let { "$it in " } ?: ""}${found.className}, in ${found.origin} " +
                "(library: read-only, not addressable; no sources, so no KDoc or declaration)"
        is FoundDefinition.NotKotlin ->
            "// ${found.name} in ${found.file}, L${found.line} (not Kotlin: read-only, not addressable)" + (found.source?.let { "\n$it" } ?: "")
        is FoundDefinition.OutsideProject ->
            "// ${found.name} in ${found.file}, L${found.line} (outside the project: read-only, not addressable)"
        is FoundDefinition.FileNotFound -> "// ${found.name} in ${found.file}, L${found.line} (file not found)"
    }

    /** The kind of Symbol a bare Symbol Path chose over same-named functions, as messages name it, e.g. `the property`. */
    private fun picked(preferred: PreferredOverFunctions): String = "the " + when (preferred.kind) {
        SymbolKind.PROPERTY, SymbolKind.VARIABLE -> "property"
        SymbolKind.ENUM_ENTRY -> "enum entry"
        SymbolKind.TYPE_PARAMETER -> "type parameter"
        SymbolKind.OTHER -> "Symbol"
        else -> preferred.kind.keyword
    }

    /** The functions it was chosen over, as a lowercase clause: `a function shares its name: edit(Int)`. */
    private fun sharedName(preferred: PreferredOverFunctions): String =
        (if (preferred.functions.size == 1) "a function shares its name: " else "functions share its name: ") +
            preferred.functions.joinToString(", ")

    /**
     * kt_edit's Reply. [types] are the Operations' types as the agent named them, for the messages of their failures;
     * [amend] is the id of the Rejected Edit the call amended, if any.
     */
    fun edit(result: EditResult, types: List<String>, amend: String?): Reply = when (result) {
        is EditResult.Written -> Reply.result(
            (result.files.map { written(it) } + collateral(result.collateral)).filter { it.isNotEmpty() }.joinToString("\n"),
        )
        is EditResult.ValidationFailed -> Reply.error(validationFailure(result.failure, "Edit", result.rejectedEdit))
        is EditResult.WriteBackFailed -> Reply.error(writeBackFailure(result.failure))
        is EditResult.NotReady ->
            result.rejectedEdit?.let {
                Reply.error("${notReadyText(result.readiness)}. The rejected Edit $it is still kept: retry with kt_edit amend: \"$it\".")
            } ?: notReady(result.readiness)
        is EditResult.OperationFailed ->
            if (result.inRejected) {
                Reply.error(
                    nothingWritten(
                        "The rejected Edit $amend no longer applies: ${operationFailure(result.failure, "operation")}",
                        "; resend the whole batch.",
                    ),
                )
            } else {
                val kept = result.rejectedEdit?.let {
                    "; the rejected Edit $it is still kept: amend it again with kt_edit amend: \"$it\" and only the fixing Operations"
                }.orEmpty()
                Reply.error(nothingWritten("Operation ${result.index}: ${operationFailure(result.failure, types[result.index])}", "$kept."))
            }
        is EditResult.NoRejectedEdit -> Reply.error(
            result.rejectedEdit?.let {
                "no rejected Edit ${result.id} to amend; the rejected Edit $it is still kept: amend it with kt_edit amend: \"$it\", " +
                    "or resend the whole batch"
            } ?: "no rejected Edit ${result.id} to amend; resend the whole batch",
        )
    }

    /** kt_rename_symbol's Reply to renaming [symbol], as the agent named it, to [newName]. */
    fun rename(result: RenameResult, symbol: String, newName: String): Reply = when (result) {
        is RenameResult.Renamed -> Reply.result(renamed(symbol, newName, result))
        is RenameResult.LookupFailed -> Reply.error(nothingWritten(sentence(lookupFailure(result.failure, ToolAdvice.RENAME_SYMBOL))))
        is RenameResult.InvalidName ->
            Reply.error("'${result.name}' is not a Kotlin identifier; pass a plain name, or one in backticks. Nothing written.")
        is RenameResult.CannotRename -> Reply.error("Cannot rename ${result.symbol}: ${result.reason}. Nothing written.")
        is RenameResult.ValidationFailed -> Reply.error(validationFailure(result.failure, "Rename"))
        is RenameResult.WriteBackFailed -> Reply.error(writeBackFailure(result.failure))
        is RenameResult.NotReady -> notReady(result.readiness)
    }

    /** The Reply of a tool that couldn't wait for kotlin-lsp to finish indexing. */
    private fun notReady(readiness: Readiness.NotReady): Reply = Reply.error(notReadyText(readiness))

    /**
     * The Reply of a tool call that threw [e]. An [AgentFacingFailure] shows its message alone; any other exception also
     * names its class, since its message may be empty or unclear on its own. A [LinkageError] means a class failed to
     * load, most likely because the install was rebuilt while this server ran, so it says to reconnect.
     */
    fun thrown(e: Throwable): Reply = Reply.error(
        when (e) {
            is AgentFacingFailure -> e.message
            is LinkageError -> "Kronstadt's installed files changed while it was running (${classAndMessage(e)}); reconnect it with /mcp."
            else -> classAndMessage(e)
        },
    )

    /**
     * What every tool says when a Symbol Path or file names no one Symbol: one wording for all of them, with the tool's
     * [advice] where it says what the tool supports or what to do instead. Messages with a list end with its last line;
     * the others end without a period only after a quoted reason. A tool that takes two files names the [argument] a file
     * check's failure came from, as its prefix.
     */
    fun lookupFailure(failure: LookupFailure, advice: ToolAdvice, argument: String? = null): String =
        argument?.let { "$it: " }.orEmpty() + lookupText(failure, advice)

    private fun lookupText(failure: LookupFailure, advice: ToolAdvice): String {
        val declaredIn = advice.declaringFile == ToolAdvice.DeclaringFile.DECLARED_IN
        return when (failure) {
            is LookupFailure.InvalidPath -> "Invalid Symbol Path '${failure.symbol}': ${failure.reason}"
            is LookupFailure.Ambiguous -> {
                val pass = when {
                    failure.file != null -> "pass one of"
                    declaredIn -> "pass one of these paths with its file as declaredIn"
                    else -> "pass one of these paths with its file"
                }
                "'${failure.symbol}' matches ${failure.candidates.size} symbols${failure.file.inFile()}; $pass:\n" +
                    candidates(failure.candidates, withFiles = failure.file == null)
            }
            // kotlin-lsp's index can drop a name (#87), and kt_find_symbol then misses it too; declaredIn doesn't need the index.
            is LookupFailure.NotFound -> "No symbol '${failure.symbol}'${failure.file.inFile()}." + when {
                failure.suggestions.isNotEmpty() -> " Close names:\n" + candidates(failure.suggestions, withFiles = failure.file == null)
                failure.file != null -> " Use kt_read_structure to list its declarations."
                declaredIn ->
                    " Use kt_find_symbol to search by partial name, or, if you know the file that declares it, pass it as declaredIn."
                else -> " Use kt_find_symbol to search by partial name."
            }
            is LookupFailure.TooManyDeclarations ->
                "'${failure.symbol}' can't be resolved without a file: so many declarations are named '${failure.name}', " +
                    "dependencies' included, that $INDEX_STOPS and may not list them all; " +
                    if (declaredIn) "pass the file that declares it as declaredIn" else "pass its file"
            is LookupFailure.SearchTimedOut ->
                "Looking up '${failure.symbol}' without a file timed out: '${failure.name}' is too common a name for kotlin-lsp " +
                    "to search in time; " + if (declaredIn) "pass the file that declares it as declaredIn" else "pass its file"
            is LookupFailure.OutOfSync ->
                "Index out of sync for ${failure.symbol} in ${failure.file}: its name is not where kotlin-lsp says; retry"
            is LookupFailure.SharedLine ->
                "${failure.symbol} shares line ${failure.line} of ${failure.file} with other code, so it has no whole-line span; " +
                    when (advice.onSharedLine) {
                        ToolAdvice.OnSharedLine.EDIT_IN_WHOLE_FILE ->
                            "use a whole-file edit_in instead: file '${failure.file}' with no symbol, and an 'old' of exact text from that file"
                        ToolAdvice.OnSharedLine.READ_ENCLOSING_SYMBOL -> "read the enclosing symbol instead"
                    }
            is LookupFailure.FileNotFound -> "File not found: ${failure.path}"
            is LookupFailure.NotKotlin -> "Not a Kotlin file: ${failure.path}. ${advice.tool} only supports .kt and .kts files."
            is LookupFailure.OutsideProject -> "Path is outside the project root: ${failure.path}"
        }
    }

    /**
     * What kt_edit and kt_rename_symbol say when Validation lets nothing be written: one wording for both. [change] names
     * what was rejected as the message opens it, e.g. `Edit` or `Rename`. A rejected Edit kept as the Rejected Edit [id]
     * is offered to amend.
     */
    private fun validationFailure(failure: ValidationFailure, change: String, id: String? = null): String = when (failure) {
        is ValidationFailure.Rejected -> buildString {
            append(change).append(" rejected")
            if (id != null) append(" (id: ").append(id).append(')')
            append(": ${counted(failure.newErrors.size, "new error")}; nothing written.\n")
            failure.newErrors.forEach { append(problem(it, withFile = true)) }
            append(
                if (id == null) {
                    "Fix what causes them and retry."
                } else {
                    "Fix with kt_edit amend: \"$id\" and only the fixing Operations, or resend the whole batch."
                },
            )
        }
        ValidationFailure.TimedOut -> "validation timed out; nothing written"
    }

    /** What kt_edit and kt_rename_symbol say when Write-back didn't write every file: one wording for both. */
    private fun writeBackFailure(failure: WriteBackFailure): String = when (failure) {
        is WriteBackFailure.ChangedOnDisk -> "${failure.file} changed on disk since read; re-read and retry"
        is WriteBackFailure.PartiallyWritten ->
            "Write-back failed part-way: writing ${failure.failed} failed (${failure.reason}). " +
                "Already written, not rolled back: ${failure.written.joinToString()}. " +
                "Nothing else was written; re-read these files before retrying."
        is WriteBackFailure.TargetExists ->
            "${failure.file} would be renamed to ${failure.target}, which already exists; nothing written. " +
                "Pick another name, or move that file first."
    }

    /** Heads the imports an Edit or rename may have made unused but kept, for want of an answer about them (#209). */
    private const val UNCHECKED_IMPORTS = "Kept imports it couldn't check (no answer from the code intelligence):"

    /**
     * A summary line with the new path, as kt_find_usages counts (`3 usages in 2 files (+1 import)`), then each changed
     * file with its usages and new warnings, then any file rename, removed imports and imports kept unchecked.
     */
    private fun renamed(symbol: String, newName: String, result: RenameResult.Renamed) = buildString {
        val usages = result.files.sumOf { it.usages }
        val imports = result.files.sumOf { it.imports }
        append("Renamed ").append(symbol).append(" to ").append(newName).append(": ")
        append(counted(usages, "usage")).append(" in ").append(counted(result.files.count { it.usages > 0 }, "file"))
        if (imports > 0) append(" (+").append(counted(imports, "import")).append(')')
        append(", and the declaration. New path: ")
        append(result.newPath ?: "unknown (several Symbols match it now; use kt_read_structure)").append('\n')
        result.files.forEachIndexed { index, file ->
            val parts = listOfNotNull(
                "declaration".takeIf { index == 0 },
                file.usages.takeIf { it > 0 }?.let { counted(it, "usage") },
                file.imports.takeIf { it > 0 }?.let { counted(it, "import") },
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
        if (result.importsUnchecked.isNotEmpty()) {
            append(UNCHECKED_IMPORTS).append('\n')
            result.importsUnchecked.forEach { append("  ").append(it.file).append(": ").append(it.import).append('\n') }
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
        if (file.importsUnchecked.isNotEmpty()) {
            append(UNCHECKED_IMPORTS).append('\n')
            file.importsUnchecked.forEach { append("  ").append(it).append('\n') }
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
            append("Collateral Errors: ${counted(count, "new error")} in files this Edit didn't change, ")
            append("which Validation doesn't check. Nothing was blocked: the Edit is written; fix them with another Edit.\n")
            collateral.errors.forEach { append(problem(it, withFile = true)) }
            if (collateral.moreErrors > 0) append("  (${collateral.moreErrors} more)\n")
        }
        if (collateral.filesUnchecked > 0) {
            append("${counted(collateral.filesUnchecked, "more file")} using the Symbols this Edit changed weren't checked for Collateral Errors.\n")
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
    private fun operationFailure(failure: OperationFailure, type: String): String = when (failure) {
        is OperationFailure.LookupFailed -> sentence(lookupFailure(failure.failure, ToolAdvice.EDIT))
        is OperationFailure.RemovedEarlier -> {
            val files = failure.files
            val several = files.size > 1
            (if (several) {
                "'${failure.symbol}' was removed or renamed by earlier Operations of this Edit in " +
                    files.dropLast(1).joinToString(", ") + " and " + files.last() + "; target what those Operations left."
            } else {
                "'${failure.symbol}' in ${files.single()} was removed or renamed by an earlier Operation of this Edit; target " +
                    "what that Operation left."
            }) +
                (if (failure.suggestions.isEmpty()) "" else " Close names:\n" + candidates(failure.suggestions, withFiles = several) + "\n")
        }
        is OperationFailure.NeedsFile ->
            "'${failure.symbol}' exists only in what earlier Operations of this Edit wrote, and a Symbol Path without a " +
                "file is looked up on disk; pass its file:\n" + candidates(failure.candidates, withFiles = true) + "\n"
        is OperationFailure.NotExactlyOnce -> {
            val miss = failure.miss
            val hint = miss.indentation
            // Missing from a Symbol a bare name chose over same-named functions: say so, as 'old' may be in one of them.
            val preferred = failure.scope.preferred
            val missingIn = where(failure.scope, preferred?.let { " (${picked(it)})" } ?: "")
            val sharedName = preferred?.let { " " + sharedName(it).replaceFirstChar(Char::uppercaseChar) + "." } ?: ""
            if (miss.lines.isEmpty() && hint != null) {
                "'old' doesn't occur in $missingIn exactly as written, but " + indentationHint(hint, "'old'") + " " +
                    (if (failure.scope.wholeFile) "Read the file to copy it." else "Copy 'old' from kt_read_symbol output.") +
                    sharedName
            } else if (miss.lines.isEmpty()) {
                "'old' doesn't occur in $missingIn, exactly as written (whitespace included; $LINE_BREAK_RULE). " +
                    (if (failure.scope.wholeFile) "Read the file to copy it." else "Use kt_read_symbol to see its source.") +
                    sharedName
            } else {
                "'old' occurs ${miss.count} times in ${where(failure.scope)}; make it longer so it occurs exactly once:\n" +
                    sourceLines(miss.lines) + "\n"
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

    /** Where a Snippet or a name occurs, one `  L<line>: <source>` line each. */
    private fun sourceLines(lines: List<SourceLine>): String = lines.joinToString("\n") { "  L${it.line}: ${it.source}" }

    /** Leading whitespace in words: `4 spaces`, `1 tab`, `1 tab and 2 spaces`, `nothing`. */
    private fun indent(whitespace: String): String {
        val tabs = whitespace.count { it == '\t' }
        val spaces = whitespace.length - tabs
        return listOfNotNull(
            tabs.takeIf { it > 0 }?.let { counted(it, "tab") },
            spaces.takeIf { it > 0 }?.let { counted(it, "space") },
        ).joinToString(" and ").ifEmpty { "nothing" }
    }

    /** One diagnostic as the agent sees it, on two lines: where it is and what it says, then the offending source line. */
    private fun problem(problem: Problem, withFile: Boolean) = buildString {
        append("  ")
        if (withFile) append(problem.file).append(':')
        append('L').append(problem.line)
        append(" in ").append(problem.symbol ?: "(file level)")
        append(": ").append(problem.message).append('\n')
        append("      ").append(problem.source).append('\n')
    }

    /** One candidate per line; resolved project-wide, each with the file to pass along with its path. */
    private fun candidates(candidates: List<SymbolCandidate>, withFiles: Boolean): String =
        candidates.joinToString("\n") { if (withFiles) "  ${it.path} — ${it.file}" else "  ${it.path}" }

    /** [message] made to end a sentence, so more can follow: a period, or a line break after a list. */
    fun sentence(message: String): String = when {
        '\n' in message -> message + "\n"
        message.endsWith('.') -> message
        else -> "$message."
    }

    /**
     * [message], a failure that ends a sentence or a list, followed by "Nothing written" and [rest]: after a space, or on its
     * own line after a list.
     */
    private fun nothingWritten(message: String, rest: String = "."): String =
        (if (message.endsWith('\n')) message else "$message ") + "Nothing written$rest"

    private fun notReadyText(readiness: Readiness.NotReady) =
        "kotlin-lsp still indexing (phase ${readiness.phase}, ${readiness.elapsed.inWholeSeconds} s elapsed); nothing returned/written, retry"

    private fun classAndMessage(e: Throwable): String = "${e::class.simpleName}: ${e.message}"

    private fun String?.inFile() = this?.let { " in $it" } ?: ""
}

/** [n] of [noun], pluralised by an `s` unless there is exactly one: `1 file`, `3 files`, `0 files`. */
private fun counted(n: Int, noun: String): String = if (n == 1) "1 $noun" else "$n ${noun}s"

/** Where a Usage or local declaration outside any Symbol is, in place of a Symbol Path. */
private const val FILE_LEVEL = "(file level)"

/** The characters a Usage's source line is cut to. */
private const val MAX_USAGE_LINE = 120

/** Why a search may be incomplete, as the tools tell the agent. */
internal const val INDEX_STOPS = "the project index stops at a maximum number of results"

/**
 * How a snippet ('old', 'at') matches line endings, as the tools state it (ADR 0004): a "line break" in the snippet,
 * a "line ending" in the file.
 */
internal const val LINE_BREAK_RULE = "any line break matches any line ending"
