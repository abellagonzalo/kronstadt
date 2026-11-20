package kronstadt.core.application

import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.addingImports
import kronstadt.core.domain.Validation
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/** A single step inside an Edit, aimed at one Symbol Path, in [file] if it is given, at a whole [file], or creating one. */
public sealed interface Operation {
    public val file: String?

    /** Null only for an [EditIn] on a whole file and for a [Create]. */
    public val symbol: String?

    /**
     * Import directives, e.g. `a.b.C`, to add to the Operation's file once it is applied, if the file lacks them.
     * Nothing else about the file's imports changes.
     */
    public val imports: List<String>
}

/** Replaces the whole Symbol Span of [symbol] with [code] (ADR 0004); nothing of the old span is kept. */
public data class Replace(
    override val file: String?,
    override val symbol: String,
    val code: String,
    override val imports: List<String> = emptyList(),
) : Operation

/** Adds [code] before the Symbol Span of [symbol] (above its KDoc and annotations), as a sibling at its indentation, one blank line above it. */
public data class InsertBefore(
    override val file: String?,
    override val symbol: String,
    val code: String,
    override val imports: List<String> = emptyList(),
) : Operation

/** Adds [code] after the Symbol Span of [symbol], as a sibling at its indentation, one blank line below it. */
public data class InsertAfter(
    override val file: String?,
    override val symbol: String,
    val code: String,
    override val imports: List<String> = emptyList(),
) : Operation

/** Removes the whole Symbol Span of [symbol] and one blank line next to it, below if there is one, else above. */
public data class Delete(
    override val file: String?,
    override val symbol: String,
    override val imports: List<String> = emptyList(),
) : Operation

/**
 * Replaces the one occurrence of [old], exactly as written, whitespace included, inside the Symbol Span of [symbol],
 * or anywhere in [file] when there is no [symbol], with [new] as written: no re-indentation, no fuzzy matching. Only
 * the line breaks in [new] become the file's.
 */
public data class EditIn(
    override val file: String?,
    override val symbol: String?,
    val old: String,
    val new: String,
    override val imports: List<String> = emptyList(),
) : Operation {
    init {
        require(symbol != null || file != null) { "edit_in needs a symbol, a file, or both" }
        require(old.isNotEmpty()) { "edit_in needs a non-empty 'old'" }
    }
}

/**
 * Creates the Kotlin [file], which mustn't exist, with [code] as its whole text, as written except that its line
 * breaks become `\n`. Validated and written back with the rest of the Edit, its missing parent directories created only
 * then. Later Operations can target its Symbols, passing [file].
 */
public data class Create(
    override val file: String,
    val code: String,
    override val imports: List<String> = emptyList(),
) : Operation {
    override val symbol: String? get() = null
}

/**
 * Use case behind `kt_edit`: applies an Edit's Operations, in order, in a Shadow Workspace, validates them and writes
 * them back as a whole (ADR 0003).
 */
public class Edit(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
    /** One budget for all of Validation's diagnostics: before and after the Edit, across every touched file. */
    private val validationTimeout: Duration = Validation.DEFAULT_TIMEOUT,
    private val writeLock: WriteLock = WriteLock(),
) {
    private val resolver = SymbolResolver(files, intelligence)

    public suspend operator fun invoke(operation: Operation): EditResult = invoke(listOf(operation))

    /**
     * Applies [operations] in order, each resolved against the text the earlier ones left, possibly across several
     * files; validates once after the last and writes every edited file back, or nothing (unless a write fails
     * part-way, see [EditResult.PartiallyWritten]).
     */
    public suspend operator fun invoke(operations: List<Operation>): EditResult {
        val paths = operations.mapIndexed { index, operation ->
            val symbol = operation.symbol ?: return@mapIndexed null
            SymbolResolver.parse(operation.file, symbol)
                .getOrElse { return EditResult.OperationFailed(index, OperationFailure.LookupFailed(it)) }
        }
        val imports = operations.mapIndexed { index, operation ->
            operation.imports.map { raw ->
                ImportDirective.parse(raw).getOrElse { return EditResult.OperationFailed(index, OperationFailure.InvalidImport(raw)) }
            }
        }
        return writeLock.withLock {
            val readiness = intelligence.awaitReady(readinessTimeout)
            if (readiness is Readiness.NotReady) return@withLock EditResult.NotReady(readiness)
            intelligence.session { session ->
                val shadow = ShadowWorkspace(files, session, resolver, validationTimeout)
                val listed = mutableMapOf<Path, MutableSet<ImportDirective>>()
                operations.zip(paths).forEachIndexed { index, (operation, parsed) ->
                    when (val applied = apply(session, shadow, operation, parsed)) {
                        is Applied.Done -> {
                            addImports(shadow, applied.file, imports[index])
                            listed.getOrPut(applied.file) { mutableSetOf() } += imports[index]
                        }
                        is Applied.Failed -> return@session EditResult.OperationFailed(index, applied.failure)
                    }
                }
                val removed = shadow.removeImportsMadeUnused(session, validationTimeout) { file, suspects -> suspects - listed[file].orEmpty() }
                    .mapKeys { files.display(it.key) }
                when (val validated = shadow.validate()) {
                    is ShadowWorkspace.Validated.Passed -> when (val writeBack = shadow.writeBack()) {
                        ShadowWorkspace.WriteBack.Written ->
                            EditResult.Written(validated.files.map { it.copy(importsRemoved = removed[it.file].orEmpty()) })
                        is ShadowWorkspace.WriteBack.ChangedOnDisk -> EditResult.ChangedOnDisk(writeBack.file)
                        is ShadowWorkspace.WriteBack.TargetExists -> error("an Edit moves no file, yet Write-back found ${writeBack.file} moved")
                        is ShadowWorkspace.WriteBack.Partial ->
                            EditResult.PartiallyWritten(writeBack.written, writeBack.failed, writeBack.reason)
                    }
                    is ShadowWorkspace.Validated.Rejected -> EditResult.Rejected(validated.newErrors)
                    ShadowWorkspace.Validated.TimedOut -> EditResult.ValidationTimedOut
                }
            }
        }
    }

    private sealed interface Applied {
        /** Applied to [file]. */
        data class Done(val file: Path) : Applied
        data class Failed(val failure: OperationFailure) : Applied
    }

    private sealed interface Located {
        data class In(val file: Path) : Located
        data class Failed(val failure: OperationFailure) : Located
    }

    /**
     * The file of [parsed], which has none. The project lookup sees only the disk, so its candidates in files this Edit
     * has edited give way to those in their current text ([ShadowWorkspace.findInEdited]). One candidate left is the
     * target if the disk has it too, else it needs its file; several are ambiguous. None leaves the disk lookup's answer:
     * a Symbol it found in an edited file is then said to be removed earlier.
     */
    private suspend fun locate(session: AnalysisSession, shadow: ShadowWorkspace, parsed: ParsedLookup): Located {
        val lookup = resolver.resolve(session, parsed)
        val onDisk: List<Pair<SymbolCandidate, Path?>> = when (lookup) {
            is Outcome.Ok -> listOf(SymbolCandidate(lookup.value.canonical, files.display(lookup.value.file)) to lookup.value.file)
            is Outcome.Failed -> when (val failure = lookup.failure) {
                is LookupFailure.Ambiguous -> failure.candidates.map { it to files.resolve(it.file) }
                is LookupFailure.NotFound -> emptyList()
                else -> return Located.Failed(OperationFailure.LookupFailed(failure))
            }
        }
        val candidates = onDisk.filterNot { (_, file) -> file != null && shadow.isEdited(file) }.map { it.first } + shadow.findInEdited(parsed)
        val candidate = candidates.singleOrNull()
        return when {
            candidates.size > 1 -> Located.Failed(OperationFailure.LookupFailed(LookupFailure.Ambiguous(parsed.symbol, null, candidates)))
            candidate != null -> onDisk.firstOrNull { (shown, _) -> shown.file == candidate.file }?.second
                ?.let { Located.In(it) }
                ?: Located.Failed(OperationFailure.NeedsFile(parsed.symbol, candidates))
            lookup is Outcome.Ok -> Located.In(lookup.value.file)
            lookup is Outcome.Failed && lookup.failure is LookupFailure.NotFound -> Located.Failed(OperationFailure.LookupFailed(lookup.failure))
            else -> Located.Failed(OperationFailure.LookupFailed(LookupFailure.NotFound(parsed.symbol, null, emptyList())))
        }
    }

    /**
     * Resolves [operation]'s Symbol against the Shadow Workspace's current text and changes the lines around its span;
     * new code is re-indented to the span's column, except an [EditIn]'s. An [EditIn] without a Symbol works on its
     * whole file instead, and a [Create] makes one.
     */
    private suspend fun apply(session: AnalysisSession, shadow: ShadowWorkspace, operation: Operation, parsed: ParsedLookup?): Applied {
        if (parsed == null) return if (operation is Create) create(shadow, operation) else applyToFile(shadow, operation as EditIn)
        // Without a file, the Symbol Path is resolved project-wide only to find its file; it is resolved again in the
        // Shadow Workspace, against the text the earlier Operations left.
        val file = parsed.file?.let { path -> classify(shadow, path).getOrElse { return Applied.Failed(OperationFailure.LookupFailed(it)) } }
            ?: when (val located = locate(session, shadow, parsed)) {
                is Located.In -> located.file
                is Located.Failed -> return Applied.Failed(located.failure)
            }
        val shown = files.display(file)
        val found = when (val lookup = shadow.resolve(file, parsed)) {
            is ShadowWorkspace.Lookup.Found -> lookup
            is ShadowWorkspace.Lookup.Failed -> return Applied.Failed(OperationFailure.LookupFailed(lookup.failure))
            is ShadowWorkspace.Lookup.RemovedEarlier ->
                return Applied.Failed(OperationFailure.RemovedEarlier(parsed.symbol, shown, lookup.suggestions))
        }
        val document = found.document
        val span = found.span
        val prefix = document.line(span.firstLine).takeWhile { it == ' ' || it == '\t' }
        fun reindented(code: String) = FileLayout.of(document).reindent(code, prefix).lines()
        // Inserted code starts and ends at its first and last non-blank lines: the one blank line comes from here.
        fun sibling(code: String) = reindented(code).dropWhile { it.isEmpty() }
        val first = span.firstLine
        val afterSpan = span.lastLine + 1
        when (operation) {
            is Create -> error("a create has no Symbol Path")
            is Replace -> shadow.replaceLines(file, first..span.lastLine, reindented(operation.code))
            is InsertBefore -> shadow.replaceLines(file, first until first, sibling(operation.code) + "")
            is InsertAfter -> shadow.replaceLines(file, afterSpan until afterSpan, listOf("") + sibling(operation.code))
            is EditIn -> when (val splice = Splice.inSpan(document, first..span.lastLine, operation)) {
                is Splice.Done -> shadow.replaceLines(file, splice.lines, splice.newLines)
                is Splice.NotExactlyOnce ->
                    return Applied.Failed(OperationFailure.NotExactlyOnce(operation.old, found.canonical, shown, splice.count, splice.lines))
                is Splice.StartsInIndentation ->
                    return Applied.Failed(OperationFailure.StartsInIndentation(found.canonical, shown, splice.line))
            }
            is Delete -> {
                // The last line is the empty one after a final newline, not a blank line between declarations.
                val blankAfter = afterSpan < document.lineCount - 1 && document.line(afterSpan).isBlank()
                val blankBefore = first > 0 && document.line(first - 1).isBlank()
                val lines = when {
                    blankAfter -> first..afterSpan
                    blankBefore -> (first - 1)..span.lastLine
                    else -> first..span.lastLine
                }
                shadow.replaceLines(file, lines, emptyList())
            }
        }
        return Applied.Done(file)
    }

    /** An [EditIn] on a whole file: its span is the whole text, and only the lines the occurrence is on change. */
    private suspend fun applyToFile(shadow: ShadowWorkspace, operation: EditIn): Applied {
        val path = checkNotNull(operation.file) { "an edit_in without a symbol has a file" }
        val file = classify(shadow, path).getOrElse { return Applied.Failed(OperationFailure.LookupFailed(it)) }
        val document = shadow.current(file) ?: return Applied.Failed(OperationFailure.LookupFailed(LookupFailure.FileNotFound(path)))
        when (val splice = Splice.inFile(document, operation)) {
            is Splice.Done -> shadow.replaceLines(file, splice.lines, splice.newLines)
            is Splice.NotExactlyOnce ->
                return Applied.Failed(OperationFailure.NotExactlyOnce(operation.old, null, files.display(file), splice.count, splice.lines))
            is Splice.StartsInIndentation ->
                return Applied.Failed(OperationFailure.StartsInIndentation(null, files.display(file), splice.line))
        }
        return Applied.Done(file)
    }

    /** A [Create]: a Kotlin file in the project that neither exists nor was touched earlier in the Edit, with its code as text. */
    private suspend fun create(shadow: ShadowWorkspace, operation: Create): Applied {
        val path = operation.file
        val file = files.resolve(path) ?: return Applied.Failed(OperationFailure.LookupFailed(LookupFailure.OutsideProject(path)))
        if (!AnalysedFile.isKotlin(file)) return Applied.Failed(OperationFailure.LookupFailed(LookupFailure.NotKotlin(path)))
        return when (shadow.create(file, operation.code.lines())) {
            null -> Applied.Done(file)
            ShadowWorkspace.NotCreated.ON_DISK -> Applied.Failed(OperationFailure.FileExists(files.display(file), inEdit = false))
            ShadowWorkspace.NotCreated.IN_EDIT -> Applied.Failed(OperationFailure.FileExists(files.display(file), inEdit = true))
        }
    }

    /** The file [path] names, as [FileStore.classify] finds it on disk, or one an earlier [Create] of this Edit made. */
    private suspend fun classify(shadow: ShadowWorkspace, path: String): Outcome<Path> {
        val onDisk = files.classify(path)
        if (onDisk is Outcome.Failed && onDisk.failure is LookupFailure.FileNotFound) {
            val created = files.resolve(path)?.takeIf { shadow.current(it) != null }
            if (created != null) return Outcome.Ok(created)
        }
        return onDisk
    }

    /** Adds those of [imports] that [file] lacks to its current text (see [addingImports]); never removes or reorders any. */
    private suspend fun addImports(shadow: ShadowWorkspace, file: Path, imports: List<ImportDirective>) {
        val document = checkNotNull(shadow.current(file)) { "$file was applied to, so it is in the Shadow Workspace" }
        val splice = document.addingImports(imports) ?: return
        shadow.replaceLines(file, splice.lines, splice.newLines)
    }

    /** [EditIn]'s change to a document: whole [lines] (0-based) to replace with [newLines]. */
    private sealed interface Splice {
        data class Done(val lines: IntRange, val newLines: List<String>) : Splice
        /** `old` occurs [count] times, on [lines]. */
        data class NotExactlyOnce(val count: Int, val lines: List<Occurrence>) : Splice
        /** `old` starts inside [line]'s (1-based) indentation: after some of its leading whitespace, not all of it. */
        data class StartsInIndentation(val line: Int) : Splice

        companion object {
            /** In a Symbol Span, [span] (0-based lines): the whole span is replaced, so it is what Validation and the result see as edited. */
            fun inSpan(document: TextDocument, span: IntRange, operation: EditIn): Splice = of(document, span, operation) { span }

            /** In a whole file, which belongs to no Symbol: only the lines the occurrence is on are replaced. */
            fun inFile(document: TextDocument, operation: EditIn): Splice =
                of(document, 0 until document.lineCount, operation) { it }

            /** Replaces the one occurrence of the Operation's `old` within [scope] lines of [document], in the [replaced] lines (given the occurrence's). */
            private fun of(document: TextDocument, scope: IntRange, operation: EditIn, replaced: (IntRange) -> IntRange): Splice {
                val text = document.text
                val old = operation.old
                val start = document.offsetOf(Position(scope.first, 0))
                val end = document.offsetOf(Position(scope.last, Int.MAX_VALUE))
                val matches = exactOccurrences(document, old, start until end)
                val match = matches.singleOrNull() ?: return NotExactlyOnce(matches.size, occurrenceLines(document, matches))
                val at = document.positionOf(match)
                val before = document.line(at.line).substring(0, at.character)
                if (at.character > 0 && before.all(::isIndentation) && text.getOrNull(match)?.let(::isIndentation) == true) {
                    return StartsInIndentation(at.line + 1)
                }
                val lines = replaced(document.positionOf(match).line..document.positionOf(match + old.length).line)
                val linesStart = document.offsetOf(Position(lines.first, 0))
                val linesEnd = document.offsetOf(Position(lines.last, Int.MAX_VALUE))
                val spliced = text.substring(linesStart, match) + operation.new + text.substring(match + old.length, linesEnd)
                return Done(lines, spliced.lines())
            }

            private fun isIndentation(char: Char) = char == ' ' || char == '\t'
        }
    }
}

/** A Symbol an Edit left in a file: its canonical Symbol Path and 1-based line span. */
public data class EditedSymbol(val path: String, val firstLine: Int, val lastLine: Int)

/** One file a successful Edit wrote back. */
public data class EditedFile(
    val file: String,
    val symbols: List<EditedSymbol>,
    val newWarnings: List<Problem>,
    /** New warnings beyond those listed in [newWarnings]. */
    val moreWarnings: Int,
    /** Errors the file had before the Edit, which never block it. */
    val preexistingErrors: Int,
    /** Imports the Edit made unused, which it removed (ADR 0004), e.g. `a.b.C`. */
    val importsRemoved: List<String> = emptyList(),
    /** Whether the Edit created the file (see [Create]). */
    val created: Boolean = false,
)

/** A diagnostic as the agent sees it: where it is ([line] is 1-based), what it says and the offending source line. */
public data class Problem(val file: String, val symbol: String?, val line: Int, val message: String, val source: String)

public sealed interface EditResult {
    public data class Written(val files: List<EditedFile>) : EditResult

    /** Validation found [newErrors] the Edit introduced; nothing was written. */
    public data class Rejected(val newErrors: List<Problem>) : EditResult

    /** [file] changed on disk after the Edit read it; nothing was written. */
    public data class ChangedOnDisk(val file: String) : EditResult

    /**
     * Write-back failed part-way: [written] are on disk, writing [failed] failed with [reason], and the Edit's other
     * files weren't written. Nothing is rolled back (ADR 0003, Consequences).
     */
    public data class PartiallyWritten(val written: List<String>, val failed: String, val reason: String) : EditResult

    /** Validation didn't finish within its timeout; nothing was written. */
    public data object ValidationTimedOut : EditResult

    /** The Operation at [index] (0-based) can't be applied; nothing was written. */
    public data class OperationFailed(val index: Int, val failure: OperationFailure) : EditResult

    /** The index was still incomplete after waiting [elapsed]; nothing was written. */
    public data class NotReady(val readiness: Readiness.NotReady) : EditResult
}

/** Why an Operation can't be applied. Paths and files are as the agent passed or should pass them. */
public sealed interface OperationFailure {
    /** Its Symbol Path or file names no one Symbol, as for any tool. */
    public data class LookupFailed(val failure: LookupFailure) : OperationFailure

    /**
     * [symbol] is in [file] on disk, but an earlier Operation of this Edit removed or renamed it; [suggestions] are
     * close names in what the earlier Operations left.
     */
    public data class RemovedEarlier(val symbol: String, val file: String, val suggestions: List<SymbolCandidate>) : OperationFailure

    /**
     * [symbol], passed without a file, isn't on disk but is in the text earlier Operations of this Edit left, at
     * [candidates]. A Symbol Path without a file is looked up in the project index, which sees only the disk.
     */
    public data class NeedsFile(val symbol: String, val candidates: List<SymbolCandidate>) : OperationFailure

    /**
     * An `edit_in`'s [text] occurs [count] times, not exactly once, in [symbol]'s span in [file], or in the whole file
     * when [symbol] is null; [lines] are where.
     */
    public data class NotExactlyOnce(
        val text: String,
        val symbol: String?,
        val file: String,
        val count: Int,
        val lines: List<Occurrence>,
    ) : OperationFailure

    /**
     * An `edit_in`'s text starts inside the indentation of [line] (1-based) of [symbol]'s span in [file], or of the
     * whole file when [symbol] is null: after some of the line's leading whitespace but not all of it. `new` would keep
     * the leftover indentation on its first line only, so its lines would no longer line up.
     */
    public data class StartsInIndentation(val symbol: String?, val file: String, val line: Int) : OperationFailure

    /** A `create`'s [file] exists on disk, or, if [inEdit], an earlier Operation of this Edit created or changed it. */
    public data class FileExists(val file: String, val inEdit: Boolean) : OperationFailure

    /** [import] isn't one import directive: a dotted name, optionally ending in `.*` or `as Alias`. */
    public data class InvalidImport(val import: String) : OperationFailure
}
