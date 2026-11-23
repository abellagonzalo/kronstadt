package kronstadt.core.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.Position
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.Validation
import kronstadt.core.domain.packageName
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.ContentHash
import kronstadt.core.port.FileStore
import kronstadt.core.port.WriteResult
import java.nio.file.Path
import kotlin.time.Duration

/**
 * The files one Edit touches, as the code intelligence sees them (ADR 0003), and the engine that applies the Edit's
 * Operations to them: it resolves Symbol Paths against the text earlier Operations left, replaces spans, validates
 * every touched file at once and writes them back all-or-nothing.
 *
 * It lives in the tool call's [AnalysisSession], which closes every document it opened when the call ends. A file is
 * read from disk the first time it's touched; that read is its base, which Validation compares with and whose hash
 * Write-back checks. A file the Edit creates has an empty base, and Write-back checks that it is still absent.
 */
internal class ShadowWorkspace(
    private val files: FileStore,
    private val session: AnalysisSession,
    private val resolver: SymbolResolver,
    /** One budget for all of Validation's diagnostics: base and current text, across every touched file. */
    private val validationTimeout: Duration,
) : DocumentSource {
    /**
     * A touched file: its [base] as read from disk with the [baseHash] Write-back checks, its [current] text and the line
     * ranges edited in [current] (a deletion edits the file but leaves no edited lines). A file the Edit creates has an
     * empty [base] and no [baseHash]: Write-back checks that it is still absent instead.
     */
    private class Touched(val base: TextDocument, val baseHash: ContentHash?) {
        var current: TextDocument = base
        val edited = mutableListOf<IntRange>()
        var isEdited = false

        /** Where Write-back moves the file to, if it is renamed. */
        var movedTo: Path? = null

        val isCreated: Boolean get() = baseHash == null
    }

    /** In the order the Edit first touched them. */
    private val touched = linkedMapOf<Path, Touched>()

    private suspend fun touch(file: Path): Touched? =
        touched[file] ?: files.readHashed(file)?.let { Touched(it.document, it.hash).also { touched[file] = it } }

    /** Why [create] made no file. */
    enum class NotCreated {
        /** The file exists on disk. */
        ON_DISK,

        /** An earlier Operation of this Edit created or touched it. */
        IN_EDIT,
    }

    /**
     * Adds [file], which mustn't exist, with [lines] joined by `\n` as its text: its base is empty, so every line is edited
     * and every error in it is new, and Write-back creates it only if it is still absent. Null once created.
     */
    suspend fun create(file: Path, lines: List<String>): NotCreated? {
        if (file in touched) return NotCreated.IN_EDIT
        if (files.readHashed(file) != null) return NotCreated.ON_DISK
        touched[file] = Touched(TextDocument(""), baseHash = null)
        replaceLines(file, 0..0, lines)
        return null
    }

    override suspend fun analysed(file: Path): AnalysedFile? {
        val document = touch(file)?.current ?: return null
        return analyse(file, document)
    }

    private suspend fun analyse(file: Path, document: TextDocument) =
        AnalysedFile(file, document, SymbolTree(session.documentSymbols(file, document)).also { trees[file to document.text] = it })

    /** Each Symbol tree analysed so far, by file and exact text: a file's Symbols depend on its text alone. */
    private val trees = mutableMapOf<Pair<Path, String>, SymbolTree>()

    /** [file]'s Symbol tree with [document] as its text, analysed unless it was before. */
    private suspend fun tree(file: Path, document: TextDocument): SymbolTree =
        trees[file to document.text] ?: SymbolTree(session.documentSymbols(file, document)).also { trees[file to document.text] = it }

    /** The Edit's Collateral Errors, once Validation passed and before Write-back (see [CollateralCheck]), within [timeout]. */
    suspend fun collateralErrors(timeout: Duration): CollateralErrors {
        val changed = touched.filterValues { it.isEdited }.map { (file, it) -> ChangedFile(file, it.base, it.current, it.isCreated) }
        return CollateralCheck(files, session, ::tree, timeout).run(changed)
    }

    sealed interface Lookup {
        /** The Symbol at [canonical] and its [span] in [document], the file's current text. */
        data class Found(val document: TextDocument, val canonical: String, val span: SymbolSpan) : Lookup
        data class Failed(val failure: LookupFailure) : Lookup

        /**
         * The Symbol is in the file as read from disk, but an earlier Operation removed or renamed it; [suggestions]
         * are close names in the current text.
         */
        data class RemovedEarlier(val suggestions: List<SymbolCandidate>) : Lookup
    }

    /** [file]'s current text, loading it from disk on first touch; null if there's no such file. */
    suspend fun current(file: Path): TextDocument? = touch(file)?.current

    /** Each file the Edit changed so far, with its text as read from disk. */
    fun editedBases(): Map<Path, TextDocument> = touched.filterValues { it.isEdited }.mapValues { it.value.base }

    /** The content hash of each file the Edit touched so far, as read from disk; null for one it creates. */
    fun baseHashes(): Map<Path, ContentHash?> = touched.mapValues { it.value.baseHash }

    /** Resolves [parsed] against [file]'s current text, loading the file from disk on first touch. */
    suspend fun resolve(file: Path, parsed: ParsedLookup): Lookup {
        val resolution = resolver.resolveIn(this, file, parsed)
            ?: return Lookup.Failed(LookupFailure.FileNotFound(parsed.file ?: files.display(file)))
        val found = resolution.getOrElse { failure ->
            if (failure is LookupFailure.NotFound && inBase(file, parsed)) return Lookup.RemovedEarlier(failure.suggestions)
            return Lookup.Failed(failure)
        }
        val span = resolver.span(found).getOrElse { return Lookup.Failed(it) }
        return Lookup.Found(found.document, found.canonical, span)
    }

    /** Whether [parsed] resolves in [file] as read from disk, which differs from its current text only once edited. */
    private suspend fun inBase(file: Path, parsed: ParsedLookup): Boolean {
        val target = touched[file]?.takeIf { it.isEdited } ?: return false
        val base = DocumentSource { analyse(file, target.base) }
        val resolution = resolver.resolveIn(base, file, parsed)
        return !(resolution is Outcome.Failed && resolution.failure is LookupFailure.NotFound)
    }

    /** Whether an Operation of this Edit has changed [file], so that its current text, not the disk's, is what counts. */
    fun isEdited(file: Path): Boolean = touched[file]?.isEdited == true

    /**
     * Where [parsed] is in the current text of the files this Edit has edited, in its package if it names one: what
     * a project-wide lookup, which sees only the disk, can't find.
     */
    suspend fun findInEdited(parsed: ParsedLookup): List<SymbolCandidate> =
        touched.filterValues { it.isEdited }.keys.flatMap { file ->
            val current = touched.getValue(file).current
            val packageName = parsed.path.packageName
            if (packageName != null && current.packageName != packageName) return@flatMap emptyList()
            when (val resolution = resolver.resolveIn(this, file, parsed)) {
                is Outcome.Ok -> listOf(SymbolCandidate(resolution.value.canonical, files.display(file)))
                is Outcome.Failed -> (resolution.failure as? LookupFailure.Ambiguous)?.candidates.orEmpty()
                null -> emptyList()
            }
        }

    /**
     * Replaces whole [lines] (0-based; empty to insert before `lines.first`) of [file]'s current text with [newLines],
     * already laid out for the file. Edited ranges below the splice move with it; ones it overlaps merge into the new
     * range.
     */
    fun replaceLines(file: Path, lines: IntRange, newLines: List<String>) {
        val target = checkNotNull(touched[file]) { "$file was never resolved in this Shadow Workspace" }
        target.current = target.current.replaceLines(lines, newLines, FileLayout.of(target.current).lineSeparator)
        target.isEdited = true
        val written = lines.first until lines.first + newLines.size
        val shift = newLines.size - lines.count()
        var merged = written
        val kept = target.edited.mapNotNull { range ->
            when {
                range.last < lines.first -> range
                range.first > lines.last -> (range.first + shift)..(range.last + shift)
                else -> {
                    merged = minOf(merged.first, range.first)..maxOf(merged.last, range.last + shift)
                    null
                }
            }
        }
        target.edited.clear()
        target.edited += (kept + listOf(merged)).filterNot { it.isEmpty() }.sortedBy { it.first }
    }

    /**
     * Applies [edits] to [file]'s current text at once, each against the text before any of them and none overlapping;
     * the lines they touch count as edited. [file] must have been touched (see [current]).
     */
    fun applyEdits(file: Path, edits: List<TextEdit>) {
        for (edit in edits.sortedByDescending { it.range.start }) {
            val document = checkNotNull(touched[file]) { "$file was never touched in this Shadow Workspace" }.current
            val lines = edit.range.start.line..edit.range.end.line
            val linesStart = document.offsetOf(Position(lines.first, 0))
            val linesEnd = document.offsetOf(Position(lines.last, Int.MAX_VALUE))
            val text = document.text
            val spliced = text.substring(linesStart, document.offsetOf(edit.range.start)) + edit.newText +
                text.substring(document.offsetOf(edit.range.end), linesEnd)
            replaceLines(file, lines, spliced.lines())
        }
    }

    /** Write-back moves [file] to [target], loading it from disk on first touch. False if there's no such file. */
    suspend fun move(file: Path, target: Path): Boolean {
        val moved = touch(file) ?: return false
        moved.movedTo = target
        moved.isEdited = true
        return true
    }

    sealed interface Validated {
        /** No errors were introduced; what each touched file looks like now, in the order the Edit touched them. */
        data class Passed(val files: List<EditedFile>) : Validated
        data class Failed(val failure: ValidationFailure) : Validated
    }

    /**
     * Errors the Edit introduced, across every touched file: each file's base diagnostics against its current text's,
     * preferring those on its edited lines. A file's diagnostics depend on the other files' text, so every base is pulled
     * while all files are at their base, and every current one only once all files are at their current text. [inBase]
     * rewrites a base diagnostic's message as it would read after the Edit (e.g. with a renamed Symbol's new name), so a
     * message that merely quotes the change isn't taken for a new error. Diagnostics the build never reports (see
     * [Validation.isSpurious]) are left out of both. All diagnostics share one [validationTimeout].
     */
    suspend fun validate(inBase: (String) -> String = { it }): Validated {
        val edited = touched.filterValues { it.isEdited }.entries.toList()
        val pulled = withTimeoutOrNull(validationTimeout) {
            val before = edited.map { (file, it) ->
                // A created file is pulled at its empty base too, so other files' base diagnostics don't see its
                // declarations; it had no errors before the Edit.
                val diagnostics = session.diagnostics(file, it.base)
                if (it.isCreated) emptyList() else Validation.withoutSpurious(file, diagnostics).map { d -> d.copy(message = inBase(d.message)) }
            }
            // Analysing every current text first is what changes every file to it.
            val trees = edited.map { (file, it) -> SymbolTree(session.documentSymbols(file, it.current)).also { tree -> this@ShadowWorkspace.trees[file to it.current.text] = tree } }
            val after = edited.map { (file, it) -> Validation.withoutSpurious(file, session.diagnostics(file, it.current)) }
            Triple(before, trees, after)
        } ?: return Validated.Failed(ValidationFailure.TimedOut)
        val (before, trees, after) = pulled
        val checked = edited.mapIndexed { i, (file, touched) -> Checked(file, touched, trees[i], before[i], after[i]) }
        val newErrors = checked.flatMap { file -> file.newErrors.map(file::problem) }
        if (newErrors.isNotEmpty()) return Validated.Failed(ValidationFailure.Rejected(newErrors))
        return Validated.Passed(checked.map { it.report() })
    }

    /** One edited file during Validation, with its current [tree] and diagnostics [before] and [after] the Edit. */
    private inner class Checked(
        private val file: Path,
        private val touched: Touched,
        private val tree: SymbolTree,
        private val before: List<Diagnostic>,
        private val after: List<Diagnostic>,
    ) {
        private val document = touched.current
        val newErrors = Validation.introduced(before, after, Severity.ERROR, touched.edited)

        /** The file's top-most Symbols that lie within an edited range, new warnings and the errors it already had. */
        fun report(): EditedFile {
            val resulting = tree.nodes.filter { node ->
                val (first, last) = SymbolSpan.lines(document, node.symbol)
                touched.edited.any { first >= it.first && last <= it.last }
            }
            val topMost = resulting.filter { node -> resulting.none { it.symbol in node.ancestors } }
            val newWarnings = Validation.introduced(before, after, Severity.WARNING, touched.edited)
            val preexistingErrors = Validation.preexisting(before, after, Severity.ERROR, touched.edited)
            return EditedFile(
                file = files.display(file),
                symbols = topMost.map { node ->
                    val (first, last) = SymbolSpan.lines(document, node.symbol)
                    EditedSymbol(tree.canonicalPath(node).toString(), first + 1, last + 1)
                },
                newWarnings = newWarnings.take(MAX_WARNINGS).map(::problem),
                moreWarnings = maxOf(newWarnings.size - MAX_WARNINGS, 0),
                preexistingErrors = preexistingErrors.take(MAX_WARNINGS).map(::problem),
                morePreexistingErrors = maxOf(preexistingErrors.size - MAX_WARNINGS, 0),
                created = touched.isCreated,
            )
        }

        /** [diagnostic], attributed to the innermost Symbol whose range holds it. */
        fun problem(diagnostic: Diagnostic): Problem = problem(files.display(file), tree, document, diagnostic)
    }

    /**
     * Write-back of every edited file, all-or-nothing as far as it can be: every hash, that every created file is still
     * absent, and that no file is moved onto an existing one, is checked before anything is written; then each file is
     * written (a moved one at its new path, its old one deleted; a created one with its missing parent directories),
     * then the code intelligence is told each file written in place is saved. Null once every file is written. A write
     * that fails after others succeeded leaves those on disk (ADR 0003, Consequences); one that fails first throws.
     */
    suspend fun writeBack(): WriteBackFailure? {
        val edited = touched.filterValues { it.isEdited }
        for ((file, it) in edited) {
            // A created file has no base hash, so this also checks that it is still absent.
            if (files.readHashed(file)?.hash != it.baseHash) return WriteBackFailure.ChangedOnDisk(files.display(file))
            val target = it.movedTo ?: continue
            if (files.read(target) != null) return WriteBackFailure.TargetExists(files.display(file), files.display(target))
        }
        val written = mutableListOf<Path>()
        try {
            for ((file, it) in edited) {
                val target = it.movedTo
                val reason = try {
                    val baseHash = it.baseHash
                    val result = when {
                        baseHash == null -> files.writeIfAbsent(file, it.current)
                        target == null -> files.writeIfUnchanged(file, baseHash, it.current)
                        else -> files.moveIfUnchanged(file, baseHash, target, it.current)
                    }
                    when (result) {
                        WriteResult.Written -> null
                        WriteResult.ChangedOnDisk -> if (written.isEmpty()) return WriteBackFailure.ChangedOnDisk(files.display(file)) else CHANGED_ON_DISK
                        WriteResult.TargetExists -> if (written.isEmpty()) {
                            return WriteBackFailure.TargetExists(files.display(file), files.display(checkNotNull(target)))
                        } else {
                            TARGET_EXISTS
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Before anything is written, a failed write is an ordinary failure: nothing to report as partial.
                    if (written.isEmpty()) throw e
                    e.message ?: e.toString()
                }
                if (reason != null) return WriteBackFailure.PartiallyWritten(written.map(files::display), files.display(file), reason)
                written.add(file)
            }
            return null
        } finally {
            // A moved file is gone from where the code intelligence has it open; closing it hands it to the file watcher.
            written.filter { touched.getValue(it).movedTo == null }.forEach { session.writtenBack(it, touched.getValue(it).current) }
        }
    }

    private companion object {
        const val MAX_WARNINGS = 5
        const val CHANGED_ON_DISK = "changed on disk since read"
        const val TARGET_EXISTS = "its new path already exists"
    }
}

/** [diagnostic] in [document], the text of the file shown as [file] whose [tree] it is, attributed to the innermost Symbol whose range holds it. */
internal fun problem(file: String, tree: SymbolTree, document: TextDocument, diagnostic: Diagnostic): Problem {
    val start = diagnostic.range.start
    val enclosing = tree.nodes.filter { start in it.symbol.range }.maxByOrNull { it.ancestors.size }
    return Problem(
        file = file,
        symbol = enclosing?.let { tree.canonicalPath(it).toString() },
        line = start.line + 1,
        message = diagnostic.message,
        source = document.line(start.line).trim(),
    )
}
