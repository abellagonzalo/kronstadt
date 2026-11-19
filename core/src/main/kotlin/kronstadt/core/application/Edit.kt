package kronstadt.core.application

import kronstadt.core.domain.FileHeader
import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.addingImports
import kronstadt.core.domain.importsDroppedIn
import kronstadt.core.domain.removingImports
import kronstadt.core.domain.Validation
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/** A single step inside an Edit, aimed at one Symbol Path, in [file] if it is given, or at a whole [file]. */
public sealed interface Operation {
    public val file: String?

    /** Null only for an [EditIn] on a whole file. */
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
    private val resolver = SymbolResolver(files, intelligence, readinessTimeout)

    public suspend operator fun invoke(operation: Operation): EditResult = invoke(listOf(operation))

    /**
     * Applies [operations] in order, each resolved against the text the earlier ones left, possibly across several
     * files; validates once after the last and writes every edited file back, or nothing (unless a write fails
     * part-way, see [EditResult.PartiallyWritten]).
     */
    public suspend operator fun invoke(operations: List<Operation>): EditResult {
        val paths = operations.mapIndexed { index, operation ->
            val symbol = operation.symbol ?: return@mapIndexed null
            val symbolPath = SymbolPath.parse(symbol)
                .getOrElse { return EditResult.OperationFailed(index, OperationFailure.InvalidPath(symbol, it.message.orEmpty())) }
            if (operation.file != null && symbolPath.packageName != null) {
                return EditResult.OperationFailed(
                    index,
                    OperationFailure.InvalidPath(symbol, "a package prefix is only for paths without a file; drop it or the file"),
                )
            }
            symbolPath
        }
        val imports = operations.mapIndexed { index, operation ->
            operation.imports.map { raw ->
                ImportDirective.parse(raw).getOrElse { return EditResult.OperationFailed(index, OperationFailure.InvalidImport(raw)) }
            }
        }
        return writeLock.withLock {
            val readiness = intelligence.awaitReady(readinessTimeout)
            if (readiness is Readiness.NotReady) return@withLock EditResult.NotReady(readiness.phase, readiness.elapsed)
            intelligence.session { session ->
                val shadow = ShadowWorkspace(files, session, resolver, validationTimeout)
                val listed = mutableMapOf<Path, MutableSet<ImportDirective>>()
                operations.zip(paths).forEachIndexed { index, (operation, symbolPath) ->
                    when (val applied = apply(session, shadow, operation, symbolPath)) {
                        is Applied.Done -> {
                            addImports(shadow, applied.file, imports[index])
                            listed.getOrPut(applied.file) { mutableSetOf() } += imports[index]
                        }
                        is Applied.Failed -> return@session EditResult.OperationFailed(index, applied.failure)
                        is Applied.NotReady -> return@session EditResult.NotReady(applied.phase, applied.elapsed)
                    }
                }
                val removed = removeImportsMadeUnused(session, shadow, listed)
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
        data class NotReady(val phase: String, val elapsed: Duration) : Applied
    }

    /**
     * Resolves [operation]'s Symbol against the Shadow Workspace's current text and changes the lines around its span;
     * new code is re-indented to the span's column, except an [EditIn]'s. An [EditIn] without a Symbol works on its
     * whole file instead.
     */
    private suspend fun apply(session: AnalysisSession, shadow: ShadowWorkspace, operation: Operation, symbolPath: SymbolPath?): Applied {
        if (symbolPath == null) return applyToFile(shadow, operation as EditIn)
        val symbol = checkNotNull(operation.symbol)
        val file = when (val located = locate(session, shadow, operation.file, symbolPath)) {
            is Located.File -> located.file
            is Located.Failed -> return Applied.Failed(located.failure)
            is Located.NotReady -> return Applied.NotReady(located.phase, located.elapsed)
        }
        val shown = files.display(file)
        val found = when (val lookup = shadow.resolve(file, symbolPath)) {
            is ShadowWorkspace.Lookup.Found -> lookup
            is ShadowWorkspace.Lookup.Ambiguous -> return Applied.Failed(OperationFailure.Ambiguous(symbol, shown, lookup.candidates))
            is ShadowWorkspace.Lookup.NotFound -> return Applied.Failed(OperationFailure.NotFound(symbol, shown, lookup.suggestions))
            is ShadowWorkspace.Lookup.RemovedEarlier ->
                return Applied.Failed(OperationFailure.RemovedEarlier(symbol, shown, lookup.suggestions))
            is ShadowWorkspace.Lookup.OutOfSync -> return Applied.Failed(OperationFailure.OutOfSync(lookup.canonical, shown))
            is ShadowWorkspace.Lookup.SharedLine ->
                return Applied.Failed(OperationFailure.SharedLine(lookup.canonical, shown, lookup.line + 1))
            ShadowWorkspace.Lookup.FileNotFound -> return Applied.Failed(OperationFailure.FileNotFound(operation.file ?: shown))
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
            is Replace -> shadow.replaceLines(file, first..span.lastLine, reindented(operation.code))
            is InsertBefore -> shadow.replaceLines(file, first until first, sibling(operation.code) + "")
            is InsertAfter -> shadow.replaceLines(file, afterSpan until afterSpan, listOf("") + sibling(operation.code))
            is EditIn -> when (val splice = Splice.inSpan(document, first..span.lastLine, operation)) {
                is Splice.Done -> shadow.replaceLines(file, splice.lines, splice.newLines)
                is Splice.NotExactlyOnce ->
                    return Applied.Failed(OperationFailure.NotExactlyOnce(operation.old, found.canonical, shown, splice.count, splice.lines))
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
        val file = when (val located = fileAt(path)) {
            is Located.File -> located.file
            is Located.Failed -> return Applied.Failed(located.failure)
            is Located.NotReady -> return Applied.NotReady(located.phase, located.elapsed)
        }
        val document = shadow.current(file) ?: return Applied.Failed(OperationFailure.FileNotFound(path))
        when (val splice = Splice.inFile(document, operation)) {
            is Splice.Done -> shadow.replaceLines(file, splice.lines, splice.newLines)
            is Splice.NotExactlyOnce ->
                return Applied.Failed(OperationFailure.NotExactlyOnce(operation.old, null, files.display(file), splice.count, splice.lines))
        }
        return Applied.Done(file)
    }

    /** Adds those of [imports] that [file] lacks to its current text (see [addingImports]); never removes or reorders any. */
    private suspend fun addImports(shadow: ShadowWorkspace, file: Path, imports: List<ImportDirective>) {
        val document = checkNotNull(shadow.current(file)) { "$file was applied to, so it is in the Shadow Workspace" }
        val splice = document.addingImports(imports) ?: return
        shadow.replaceLines(file, splice.lines, splice.newLines)
    }

    /**
     * Removes from each file the Edit changed the imports it made unused (ADR 0004): those the code intelligence's
     * organize-imports drops from the file's current text but not from its base, other than those its Operations [listed]
     * for it. Without an answer an import counts as used. Returns those removed, by displayed file.
     */
    private suspend fun removeImportsMadeUnused(
        session: AnalysisSession,
        shadow: ShadowWorkspace,
        listed: Map<Path, Set<ImportDirective>>,
    ): Map<String, List<String>> = shadow.editedBases().mapNotNull { (file, base) ->
        val current = checkNotNull(shadow.current(file)) { "$file was edited, so it is in the Shadow Workspace" }
        val before = FileHeader.of(base.text).imports.map { it.directive }.toSet()
        if (before.isEmpty()) return@mapNotNull null
        val unusedNow = current.importsDroppedIn(session.organizeImports(file, current) ?: return@mapNotNull null)
        // Only an import the base had can have been used before; most Edits stop here, with no second round-trip.
        val candidates = unusedNow.intersect(before) - listed[file].orEmpty()
        if (candidates.isEmpty()) return@mapNotNull null
        val unusedBefore = base.importsDroppedIn(session.organizeImports(file, base) ?: return@mapNotNull null)
        val removal = current.removingImports(candidates - unusedBefore) ?: return@mapNotNull null
        shadow.replaceLines(file, removal.splice.lines, removal.splice.newLines)
        files.display(file) to removal.removed.map { it.text }
    }.toMap()

    /** [EditIn]'s change to a document: whole [lines] (0-based) to replace with [newLines]. */
    private sealed interface Splice {
        data class Done(val lines: IntRange, val newLines: List<String>) : Splice
        /** `old` occurs [count] times, on [lines]. */
        data class NotExactlyOnce(val count: Int, val lines: List<Occurrence>) : Splice

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
                val lines = replaced(document.positionOf(match).line..document.positionOf(match + old.length).line)
                val linesStart = document.offsetOf(Position(lines.first, 0))
                val linesEnd = document.offsetOf(Position(lines.last, Int.MAX_VALUE))
                val spliced = text.substring(linesStart, match) + operation.new + text.substring(match + old.length, linesEnd)
                return Done(lines, spliced.lines())
            }
        }
    }

    private sealed interface Located {
        data class File(val file: Path) : Located
        data class Failed(val failure: OperationFailure) : Located
        data class NotReady(val phase: String, val elapsed: Duration) : Located
    }

    /** The Kotlin file at [path], as the agent passed it. */
    private fun fileAt(path: String): Located {
        val file = files.resolve(path) ?: return Located.Failed(OperationFailure.OutsideProject(path))
        if (!AnalysedFile.isKotlin(file)) return Located.Failed(OperationFailure.NotKotlin(path))
        return Located.File(file)
    }

    /**
     * The file the Operation targets. Without one, the Symbol Path is resolved project-wide only to find its file;
     * it is resolved again in the Shadow Workspace, against the text the earlier Operations left. The project index
     * sees only the disk, so a Symbol that exists only in that text needs its file.
     */
    private suspend fun locate(session: AnalysisSession, shadow: ShadowWorkspace, path: String?, symbolPath: SymbolPath): Located {
        if (path != null) return fileAt(path)
        val symbol = symbolPath.toString()
        return when (val resolution = resolver.resolve(session, null, symbolPath)) {
            is SymbolResolver.Resolution.Found -> Located.File(resolution.file)
            is SymbolResolver.Resolution.Ambiguous -> Located.Failed(OperationFailure.Ambiguous(symbol, null, resolution.candidates))
            is SymbolResolver.Resolution.NotFound -> {
                val inEdit = shadow.findInEdited(symbolPath)
                Located.Failed(
                    if (inEdit.isNotEmpty()) OperationFailure.NeedsFile(symbol, inEdit)
                    else OperationFailure.NotFound(symbol, null, resolution.suggestions),
                )
            }
            is SymbolResolver.Resolution.NotReady -> Located.NotReady(resolution.phase, resolution.elapsed)
            is SymbolResolver.Resolution.FileNotFound -> Located.Failed(OperationFailure.FileNotFound(resolution.path))
            is SymbolResolver.Resolution.NotKotlin -> Located.Failed(OperationFailure.NotKotlin(resolution.path))
            is SymbolResolver.Resolution.OutsideProject -> Located.Failed(OperationFailure.OutsideProject(resolution.path))
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
    public data class NotReady(val phase: String, val elapsed: Duration) : EditResult
}

/** Why an Operation can't be applied. Paths and files are as the agent passed or should pass them. */
public sealed interface OperationFailure {
    public data class InvalidPath(val symbol: String, val reason: String) : OperationFailure

    /** [file] is null when the path was resolved project-wide; each candidate names its own file. */
    public data class Ambiguous(val symbol: String, val file: String?, val candidates: List<SymbolCandidate>) : OperationFailure
    public data class NotFound(val symbol: String, val file: String?, val suggestions: List<SymbolCandidate>) : OperationFailure

    /** The text at the Symbol's name range isn't its name, so its range can't be trusted. */
    public data class OutOfSync(val symbol: String, val file: String) : OperationFailure

    /** Something other than whitespace shares 1-based [line] with the Symbol, so it has no whole-line span. */
    public data class SharedLine(val symbol: String, val file: String, val line: Int) : OperationFailure

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

    public data class FileNotFound(val path: String) : OperationFailure
    public data class NotKotlin(val path: String) : OperationFailure
    public data class OutsideProject(val path: String) : OperationFailure

    /** [import] isn't one import directive: a dotted name, optionally ending in `.*` or `as Alias`. */
    public data class InvalidImport(val import: String) : OperationFailure
}
