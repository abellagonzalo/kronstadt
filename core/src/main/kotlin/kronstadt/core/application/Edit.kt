package kronstadt.core.application

import kronstadt.core.domain.AnalysedFile
import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.Position
import kronstadt.core.domain.PreferredOverFunctions
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolCandidate
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.addingImports
import kronstadt.core.domain.Validation
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.ContentHash
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
 * line breaks are loose: each in [old] matches any line ending in the file, and those in [new] become the file's.
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
 * Use case behind an Edit: applies an Edit's Operations, in order, in a Shadow Workspace, validates them and writes
 * them back as a whole (ADR 0003).
 */
public class Edit(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
    /** One budget for all of Validation's diagnostics: before and after the Edit, across every touched file. */
    private val validationTimeout: Duration = Validation.DEFAULT_TIMEOUT,
    private val writeCalls: WriteCalls = WriteCalls(),
    /** The budget for looking for Collateral Errors once Validation passed; running out of it never fails the Edit. */
    private val collateralTimeout: Duration = validationTimeout,
) {
    private val resolver = SymbolResolver(files, intelligence)

    public suspend operator fun invoke(operation: Operation): EditResult = invoke(listOf(operation))

    /**
     * Applies [operations] in order, each resolved against the text the earlier ones left, possibly across several
     * files; validates once after the last and writes every edited file back, or nothing (unless a write fails
     * part-way, see [WriteBackFailure.PartiallyWritten]).
     *
     * With [amend], the id of the last Rejected Edit, [operations] are appended to its Operations and the combined Edit is
     * applied, validated and written as one, provided no file it touched changed on disk since it read them. Each outcome
     * settles what [writeCalls] keeps of the Rejected Edit (see [Settlement]).
     */
    public suspend operator fun invoke(operations: List<Operation>, amend: String? = null): EditResult = writeCalls.edit(amend) { rejected ->
        if (rejected == null) return@edit apply(emptyList(), operations, emptyMap())
        changedSince(rejected.bases)?.let { return@edit Settled(EditResult.WriteBackFailed(WriteBackFailure.ChangedOnDisk(it)), Settlement.Drop) }
        apply(rejected.operations, operations, rejected.bases)
    }

    /** The first of [bases]' files whose content hash on disk is no longer its base's (or that now exists, for a null one), as shown. */
    private suspend fun changedSince(bases: Map<Path, ContentHash?>): String? =
        bases.entries.firstOrNull { (file, hash) -> files.readHashed(file)?.hash != hash }?.let { files.display(it.key) }

    /**
     * Applies [kept], the Operations of a Rejected Edit read at [bases], then [operations], as one Edit; indexes in its
     * result count [operations], or [kept]'s if one of those fails. Kept as the Rejected Edit if Validation rejects it;
     * restored if one of [operations] fails or the index isn't ready; dropped otherwise.
     */
    private suspend fun apply(kept: List<Operation>, operations: List<Operation>, bases: Map<Path, ContentHash?>): Settled {
        val combined = kept + operations
        fun failed(index: Int, failure: OperationFailure) =
            if (index < kept.size) {
                Settled(EditResult.OperationFailed(index, failure, inRejected = true), Settlement.Drop)
            } else {
                Settled(EditResult.OperationFailed(index - kept.size, failure), Settlement.Restore)
            }
        val paths = combined.mapIndexed { index, operation ->
            val symbol = operation.symbol ?: return@mapIndexed null
            SymbolResolver.parse(operation.file, symbol).getOrElse { return failed(index, OperationFailure.LookupFailed(it)) }
        }
        val imports = combined.mapIndexed { index, operation ->
            operation.imports.map { raw ->
                ImportDirective.parse(raw).getOrElse { return failed(index, OperationFailure.InvalidImport(raw)) }
            }
        }
        combined.forEachIndexed { index, operation ->
            val inserted = when (operation) {
                is InsertBefore -> operation.code
                is InsertAfter -> operation.code
                else -> return@forEachIndexed
            }
            if (inserted.isBlank()) return failed(index, OperationFailure.BlankCode)
        }
        val readiness = intelligence.awaitReady(readinessTimeout)
        if (readiness is Readiness.NotReady) return Settled(EditResult.NotReady(readiness), Settlement.Restore)
        return intelligence.session { session ->
            val shadow = ShadowWorkspace(files, session, resolver, validationTimeout)
            val listed = mutableMapOf<Path, MutableSet<ImportDirective>>()
            combined.zip(paths).forEachIndexed { index, (operation, parsed) ->
                when (val applied = apply(shadow, operation, parsed)) {
                    is Applied.Done -> {
                        addImports(shadow, applied.file, imports[index])
                        listed.getOrPut(applied.file) { mutableSetOf() } += imports[index]
                    }
                    is Applied.Failed -> return@session failed(index, applied.failure)
                }
            }
            // A file can change on disk between an amend's check and its replay reading it: the Edit is based on the first read.
            val read = shadow.baseHashes()
            bases.entries.firstOrNull { (file, hash) -> file in read && read[file] != hash }
                ?.let { return@session Settled(EditResult.WriteBackFailed(WriteBackFailure.ChangedOnDisk(files.display(it.key))), Settlement.Drop) }
            val removed = shadow.removeImportsMadeUnused(session, validationTimeout) { file, imports -> imports - listed[file].orEmpty() }
                .mapKeys { files.display(it.key) }
            when (val validated = shadow.validate()) {
                is ShadowWorkspace.Validated.Passed -> {
                    val collateral = shadow.collateralErrors(collateralTimeout)
                    val written = shadow.writeBack()?.let { EditResult.WriteBackFailed(it) }
                        ?: EditResult.Written(validated.files.map { it.copy(importsRemoved = removed[it.file].orEmpty()) }, collateral)
                    Settled(written, Settlement.Drop)
                }
                is ShadowWorkspace.Validated.Failed -> when (val failure = validated.failure) {
                    is ValidationFailure.Rejected -> Settled(EditResult.ValidationFailed(failure), Settlement.Keep(combined, read))
                    ValidationFailure.TimedOut -> Settled(EditResult.ValidationFailed(failure), Settlement.Drop)
                }
            }
        }
    }

    private sealed interface Applied {
        /** Applied to [file]. */
        data class Done(val file: Path) : Applied
        data class Failed(val failure: OperationFailure) : Applied
    }

    /**
     * Changes the lines around the span of [operation]'s Symbol, as the Shadow Workspace locates it; new code is
     * re-indented to the span's column, except an [EditIn]'s. An [EditIn] without a Symbol works on its whole file
     * instead, and a [Create] makes one.
     */
    private suspend fun apply(shadow: ShadowWorkspace, operation: Operation, parsed: ParsedLookup?): Applied {
        if (parsed == null) return if (operation is Create) create(shadow, operation) else applyToFile(shadow, operation as EditIn)
        val found = when (val located = shadow.locate(parsed)) {
            is ShadowWorkspace.Located.Found -> located
            is ShadowWorkspace.Located.Failed -> return Applied.Failed(located.failure)
        }
        val file = found.file
        val document = found.document
        val span = found.span
        val prefix = FileLayout.indentOf(document.line(span.firstLine))
        fun reindented(code: String) = FileLayout.of(document).reindent(code, prefix).lines()
        val replacement = when (operation) {
            is Create -> error("a create has no Symbol Path")
            is Replace -> span.replacedBy(reindented(operation.code))
            is InsertBefore -> span.siblingBefore(reindented(operation.code))
            is InsertAfter -> span.siblingAfter(reindented(operation.code))
            is EditIn -> return applySplice(
                shadow, file, Splice.inSpan(document, span.firstLine..span.lastLine, operation), EditInScope(found.canonical, files.display(file), found.preferred),
            )
            is Delete -> span.deletion(document)
        }
        shadow.replaceLines(file, replacement.lines, replacement.newLines)
        return Applied.Done(file)
    }

    /**
     * An [EditIn] on a whole file: its span is the whole text. The lines replaced are the innermost Symbol Span holding
     * the lines it changes, as an [EditIn] on that Symbol would replace (#62, #129), or only those lines outside any
     * Symbol (see [Splice.Done.narrowed] for lines only inserted or deleted).
     */
    private suspend fun applyToFile(shadow: ShadowWorkspace, operation: EditIn): Applied {
        val path = checkNotNull(operation.file) { "an edit_in without a symbol has a file" }
        val file = shadow.fileNamed(path).getOrElse { return Applied.Failed(OperationFailure.LookupFailed(it)) }
        val analysed = shadow.analysed(file) ?: return Applied.Failed(OperationFailure.LookupFailed(LookupFailure.FileNotFound(path)))
        val document = analysed.document
        // Lines outside the document (before the first, after the last) lie in no Symbol Span.
        val splice = Splice.inFile(document, operation) { held -> analysed.innermostSpanHolding(held)?.let { it.firstLine..it.lastLine } }
        return applySplice(shadow, file, splice, EditInScope(null, files.display(file)))
    }

    /** Makes [splice]'s change to [file], or fails the `edit_in` whose `old` was looked for in [scope]. */
    private fun applySplice(shadow: ShadowWorkspace, file: Path, splice: Splice, scope: EditInScope): Applied {
        when (splice) {
            is Splice.Done -> shadow.replaceLines(file, splice.lines, splice.newLines)
            is Splice.NotExactlyOnce -> return Applied.Failed(OperationFailure.NotExactlyOnce(scope, splice.miss))
            is Splice.StartsInIndentation -> return Applied.Failed(OperationFailure.StartsInIndentation(scope, splice.line + 1))
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

    /** Adds those of [imports] that [file] lacks to its current text (see [addingImports]); never removes or reorders any. */
    private suspend fun addImports(shadow: ShadowWorkspace, file: Path, imports: List<ImportDirective>) {
        val document = checkNotNull(shadow.current(file)) { "$file was applied to, so it is in the Shadow Workspace" }
        val splice = document.addingImports(imports) ?: return
        shadow.replaceLines(file, splice.lines, splice.newLines)
    }

    /** [EditIn]'s change to a document: whole [lines] (0-based) to replace with [newLines]. */
    private sealed interface Splice {
        data class Done(val lines: IntRange, val newLines: List<String>) : Splice {
            /**
             * This splice cut to the lines it changes, which may be none (a pure insertion), then widened to the lines
             * [holding] gives for the lines that must lie in one Symbol Span for it to count as that Symbol's edit, or none
             * wider if it gives null. The text it leaves is the same.
             *
             * Lines only inserted or deleted, where the other side is all shared lines, could as well be placed anywhere
             * from the earliest place the shared lines below them allow to the latest the shared lines above them allow:
             * every such place must lie in the span, and lines only inserted also the lines on both sides of them. They
             * are placed at the latest, as agents add code after the lines they quote.
             */
            fun narrowed(document: TextDocument, holding: (IntRange) -> IntRange?): Done {
                val before = lines.map(document::line)
                val shorter = minOf(before.size, newLines.size)
                val prefix = sharedLines(before, newLines, shorter)
                val suffix = sharedLines(before.asReversed(), newLines.asReversed(), shorter)
                val pure = prefix + suffix >= shorter
                val kept = if (pure) shorter - prefix else suffix
                val beforeEnd = before.size - kept
                val changed = (lines.first + prefix) until (lines.first + beforeEnd)
                val earliest = lines.first + if (pure) maxOf(0, shorter - suffix) else prefix
                val held = if (changed.isEmpty()) (earliest - 1)..changed.first else earliest until changed.last + 1
                val widened = holding(held) ?: changed
                val written = newLines.subList(prefix, newLines.size - kept)
                return Done(
                    widened,
                    (widened.first until changed.first).map(document::line) + written + (changed.last + 1..widened.last).map(document::line),
                )
            }

            /** How many of the first [most] lines [a] and [b] share, from their start. */
            private fun sharedLines(a: List<String>, b: List<String>, most: Int): Int =
                (0 until most).firstOrNull { a[it] != b[it] } ?: most
        }
        /** `old` doesn't occur exactly once: [miss] says how often and where. */
        data class NotExactlyOnce(val miss: Snippet.Miss) : Splice
        /** `old` starts inside [line]'s (0-based) indentation: after some of its leading whitespace, not all of it. */
        data class StartsInIndentation(val line: Int) : Splice

        companion object {
            /** In a Symbol Span, [span] (0-based lines): the whole span is replaced, so it is what Validation and the result see as edited. */
            fun inSpan(document: TextDocument, span: IntRange, operation: EditIn): Splice = of(document, span, operation) { span }

            /**
             * In a whole file: the lines the splice changes, widened to those [holding] gives for them (see
             * [Done.narrowed]; null: none wider), are replaced, so they are what Validation and the result see as edited
             * (#129).
             */
            fun inFile(document: TextDocument, operation: EditIn, holding: (IntRange) -> IntRange?): Splice =
                when (val splice = of(document, 0 until document.lineCount, operation) { it }) {
                    is Done -> splice.narrowed(document, holding)
                    else -> splice
                }

            /** Replaces the one occurrence of the Operation's `old` within [scope] lines of [document], in the [replaced] lines (given the occurrence's). */
            private fun of(document: TextDocument, scope: IntRange, operation: EditIn, replaced: (IntRange) -> IntRange): Splice {
                val text = document.text
                val start = document.offsetOf(Position(scope.first, 0))
                val end = document.offsetOf(Position(scope.last, Int.MAX_VALUE))
                val match = when (val found = Snippet.find(document, operation.old, start until end)) {
                    is Snippet.Match -> found.range
                    is Snippet.Miss -> return NotExactlyOnce(found)
                }
                val at = document.positionOf(match.first)
                if (FileLayout.isInsideIndent(document.line(at.line), at.character)) return StartsInIndentation(at.line)
                val lines = replaced(at.line..document.positionOf(match.last + 1).line)
                val linesStart = document.offsetOf(Position(lines.first, 0))
                val linesEnd = document.offsetOf(Position(lines.last, Int.MAX_VALUE))
                val spliced = text.substring(linesStart, match.first) + operation.new + text.substring(match.last + 1, linesEnd)
                return Done(lines, spliced.lines())
            }
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
    /** Errors the file had before the Edit, which never block it; capped like [newWarnings]. */
    val preexistingErrors: List<Problem>,
    /** Errors the file had before the Edit beyond those listed in [preexistingErrors]. */
    val morePreexistingErrors: Int,
    /** Imports the Edit made unused, which it removed (ADR 0004), e.g. `a.b.C`. */
    val importsRemoved: List<String> = emptyList(),
    /** Whether the Edit created the file (see [Create]). */
    val created: Boolean = false,
)

/** A diagnostic as the agent sees it: where it is ([line] is 1-based), what it says and the offending source line. */
public data class Problem(val file: String, val symbol: String?, val line: Int, val message: String, val source: String)

/**
 * The Collateral Errors a written Edit introduced: compiler errors in files it didn't touch, which Validation never
 * checks and which never block the Edit. They are looked for only when the Edit changed the header of a Symbol that
 * existed before it (removed or renamed it, or changed its kind, receiver, parameters or return type), or added a direct
 * subtype of a sealed type that existed before it, in the files that used such a Symbol or sealed type before the Edit,
 * at most [MAX_FILES] of them (see [CollateralCheck]).
 */
public data class CollateralErrors(
    /** At most [MAX_LISTED] of them: file by file, in the order their Usages were found, and by line within a file. */
    val errors: List<Problem> = emptyList(),
    /** Collateral Errors beyond those listed in [errors]. */
    val moreErrors: Int = 0,
    /** Files with Usages of a changed Symbol or an extended sealed type beyond the [MAX_FILES] checked, which weren't checked. */
    val filesUnchecked: Int = 0,
    /** Why the check didn't finish, so other files may have errors it didn't find; null if it finished. */
    val unfinished: Unfinished? = null,
) {
    /** Why the check of other files didn't finish; the Edit is written either way. */
    public sealed interface Unfinished {
        /** The check ran out of its time budget. */
        public data object TimedOut : Unfinished

        /** The check failed with [reason]. */
        public data class Failed(val reason: String) : Unfinished
    }

    public companion object {
        /** The most files with Usages the check looks at. */
        public const val MAX_FILES: Int = 20

        /** The most Collateral Errors a result lists. */
        public const val MAX_LISTED: Int = 10
    }
}

public sealed interface EditResult {
    /**
     * The id of the Rejected Edit kept after this call, to amend: a new one, or the one amended, put back because the
     * amend failed through no fault of its; null when none is kept.
     */
    public val rejectedEdit: String?

    /** The Edit was written to [files]; [collateral] are the errors it introduced in files it didn't touch. */
    public data class Written(
        val files: List<EditedFile>,
        val collateral: CollateralErrors = CollateralErrors(),
        override val rejectedEdit: String? = null,
    ) : EditResult

    /**
     * Validation let nothing be written, for [failure]. When it [ValidationFailure.Rejected] the Edit, the Edit is kept as
     * the Rejected Edit [rejectedEdit] until the next write call, so a follow-up can amend it.
     */
    public data class ValidationFailed(val failure: ValidationFailure, override val rejectedEdit: String? = null) : EditResult

    /** Write-back didn't write every file, for [failure]; a file changed on disk since the Edit read it is one. */
    public data class WriteBackFailed(val failure: WriteBackFailure, override val rejectedEdit: String? = null) : EditResult

    /**
     * The Operation at [index] (0-based) can't be applied; nothing was written. In an amend, [index] counts the amend's
     * Operations, unless [inRejected]: then it is one of the Rejected Edit's, which no longer applies and is dropped.
     * Otherwise the Rejected Edit is still kept, as [rejectedEdit], to amend again.
     */
    public data class OperationFailed(
        val index: Int,
        val failure: OperationFailure,
        val inRejected: Boolean = false,
        override val rejectedEdit: String? = null,
    ) : EditResult

    /**
     * There is no Rejected Edit [id] to amend: it never was, or a later write call dropped it. Nothing was written. The
     * current Rejected Edit, if any, is still kept as [rejectedEdit].
     */
    public data class NoRejectedEdit(val id: String, override val rejectedEdit: String? = null) : EditResult

    /** The index was still incomplete after the wait [readiness] gives; nothing was written. In an amend, the Rejected Edit is still kept as [rejectedEdit]. */
    public data class NotReady(val readiness: Readiness.NotReady, override val rejectedEdit: String? = null) : EditResult
}

/**
 * Where an `edit_in` looks for its text: [symbol]'s span in [file], or the whole file when [symbol] is null. [preferred]
 * is set when [symbol], a bare name, chose a non-function over same-named functions (ADR 0002).
 */
public data class EditInScope(val symbol: String?, val file: String, val preferred: PreferredOverFunctions? = null) {
    /** Whether the `edit_in` works on the whole file rather than a Symbol's span. */
    val wholeFile: Boolean get() = symbol == null
}

/** Why an Operation can't be applied. Paths and files are as the agent passed or should pass them. */
public sealed interface OperationFailure {
    /** Its Symbol Path or file names no one Symbol, as for any tool. */
    public data class LookupFailed(val failure: LookupFailure) : OperationFailure

    /**
     * [symbol] is on disk in [files], one or more, but earlier Operations of this Edit removed or renamed it in each of them;
     * [suggestions] are close names in what those Operations left. Several files only when [symbol] was passed without a file.
     */
    public data class RemovedEarlier(val symbol: String, val files: List<String>, val suggestions: List<SymbolCandidate>) : OperationFailure {
        init {
            require(files.isNotEmpty()) { "'$symbol' must have been removed earlier in at least one file" }
        }
    }

    /**
     * [symbol], passed without a file, isn't on disk but is in the text earlier Operations of this Edit left, at
     * [candidates]. A Symbol Path without a file is looked up in the project index, which sees only the disk.
     */
    public data class NeedsFile(val symbol: String, val candidates: List<SymbolCandidate>) : OperationFailure

    /** An `edit_in`'s text doesn't occur exactly once in [scope]: [miss] says how often and where. */
    public data class NotExactlyOnce(val scope: EditInScope, val miss: Snippet.Miss) : OperationFailure

    /**
     * An `edit_in`'s text, in [scope], starts inside the indentation of its file's [line] (1-based): after some of the
     * line's leading whitespace but not all of it. `new` would keep the leftover indentation on its first line only, so
     * its lines would no longer line up.
     */
    public data class StartsInIndentation(val scope: EditInScope, val line: Int) : OperationFailure

    /** A `create`'s [file] exists on disk, or, if [inEdit], an earlier Operation of this Edit created or changed it. */
    public data class FileExists(val file: String, val inEdit: Boolean) : OperationFailure

    /** [import] isn't one import directive: a dotted name, optionally ending in `.*` or `as Alias`. */
    public data class InvalidImport(val import: String) : OperationFailure

    /** An `insert_before` or `insert_after` whose code has no non-blank line, so it would insert nothing. */
    public data object BlankCode : OperationFailure
}
