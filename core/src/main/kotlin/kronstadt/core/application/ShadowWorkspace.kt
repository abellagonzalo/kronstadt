package kronstadt.core.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.domain.Validation
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.FileStore
import kronstadt.core.port.HashedDocument
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
 * Write-back checks.
 */
internal class ShadowWorkspace(
    private val files: FileStore,
    private val session: AnalysisSession,
    private val resolver: SymbolResolver,
    /** One budget for all of Validation's diagnostics: base and current text, across every touched file. */
    private val validationTimeout: Duration,
) : DocumentSource {
    /** A touched file: its [base] as read from disk, its [current] text and the line ranges edited in [current]. */
    private class Touched(val base: HashedDocument) {
        var current: TextDocument = base.document
        val edited = mutableListOf<IntRange>()
    }

    /** In the order the Edit first touched them. */
    private val touched = linkedMapOf<Path, Touched>()

    private suspend fun touch(file: Path): Touched? =
        touched[file] ?: files.readHashed(file)?.let { Touched(it).also { touched[file] = it } }

    override suspend fun analysed(file: Path): AnalysedFile? {
        val document = touch(file)?.current ?: return null
        return AnalysedFile(file, document, SymbolTree(session.documentSymbols(file, document)))
    }

    sealed interface Lookup {
        /** The Symbol at [canonical] and its [span] in [document], the file's current text. */
        data class Found(val document: TextDocument, val canonical: String, val span: SymbolSpan) : Lookup
        data class Ambiguous(val candidates: List<SymbolCandidate>) : Lookup
        data class NotFound(val suggestions: List<SymbolCandidate>) : Lookup

        /** The text at the Symbol's name range isn't its name, so its range can't be trusted. */
        data class OutOfSync(val canonical: String) : Lookup

        /** Something other than whitespace shares 0-based [line] with the Symbol, so it has no whole-line span. */
        data class SharedLine(val canonical: String, val line: Int) : Lookup
        data object FileNotFound : Lookup
    }

    /** Resolves [symbol] against [file]'s current text, loading the file from disk on first touch. */
    suspend fun resolve(file: Path, symbol: SymbolPath): Lookup {
        val found = when (val resolution = resolver.resolveIn(this, file, symbol)) {
            null -> return Lookup.FileNotFound
            is SymbolResolver.Resolution.Found -> resolution
            is SymbolResolver.Resolution.Ambiguous -> return Lookup.Ambiguous(resolution.candidates)
            is SymbolResolver.Resolution.NotFound -> return Lookup.NotFound(resolution.suggestions)
            else -> error("in-file resolution reported $resolution")
        }
        val canonical = found.tree.canonicalPath(found.node).toString()
        return when (val computation = SymbolSpan.of(found.document, found.node.symbol)) {
            is SymbolSpan.Computation.Computed -> Lookup.Found(found.document, canonical, computation.span)
            SymbolSpan.Computation.OutOfSync -> Lookup.OutOfSync(canonical)
            is SymbolSpan.Computation.SharedLine -> Lookup.SharedLine(canonical, computation.line)
        }
    }

    /**
     * Replaces whole [lines] (0-based) of [file]'s current text with [code], already laid out for the file. Edited
     * ranges below the splice move with it; ones it overlaps merge into the new range.
     */
    fun replaceSpan(file: Path, lines: IntRange, code: String) {
        val target = checkNotNull(touched[file]) { "$file was never resolved in this Shadow Workspace" }
        target.current = target.current.replace(TextRange(Position(lines.first, 0), Position(lines.last, Int.MAX_VALUE)), code)
        val newLines = lines.first until lines.first + code.lines().size
        val shift = newLines.count() - lines.count()
        var merged = newLines
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
        target.edited += (kept + listOf(merged)).sortedBy { it.first }
    }

    sealed interface Validated {
        /** No errors were introduced; what each touched file looks like now, in the order the Edit touched them. */
        data class Passed(val files: List<EditedFile>) : Validated
        data class Rejected(val newErrors: List<Problem>) : Validated
        data object TimedOut : Validated
    }

    /**
     * Errors the Edit introduced, across every touched file: each file's base diagnostics against its current text's,
     * preferring those on its edited lines. All diagnostics share one [validationTimeout].
     */
    suspend fun validate(): Validated {
        val edited = touched.filterValues { it.edited.isNotEmpty() }
        val diagnostics = withTimeoutOrNull(validationTimeout) {
            edited.map { (file, it) -> session.diagnostics(file, it.base.document) to session.diagnostics(file, it.current) }
        } ?: return Validated.TimedOut
        val checked = edited.entries.zip(diagnostics) { (file, touched), (before, after) ->
            Checked(file, touched, SymbolTree(session.documentSymbols(file, touched.current)), before, after)
        }
        val newErrors = checked.flatMap { file -> file.newErrors.map(file::problem) }
        if (newErrors.isNotEmpty()) return Validated.Rejected(newErrors)
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
            return EditedFile(
                file = files.display(file),
                symbols = topMost.map { node ->
                    val (first, last) = SymbolSpan.lines(document, node.symbol)
                    EditedSymbol(tree.canonicalPath(node).toString(), first + 1, last + 1)
                },
                newWarnings = newWarnings.take(MAX_WARNINGS).map(::problem),
                moreWarnings = maxOf(newWarnings.size - MAX_WARNINGS, 0),
                preexistingErrors = after.count { it.severity == Severity.ERROR } - newErrors.size,
            )
        }

        /** [diagnostic], attributed to the innermost Symbol whose range holds it. */
        fun problem(diagnostic: Diagnostic): Problem {
            val start = diagnostic.range.start
            val enclosing = tree.nodes.filter { start in it.symbol.range }.maxByOrNull { it.ancestors.size }
            return Problem(
                file = files.display(file),
                symbol = enclosing?.let { tree.canonicalPath(it).toString() },
                line = start.line + 1,
                message = diagnostic.message,
                source = document.line(start.line).trim(),
            )
        }
    }

    sealed interface WriteBack {
        data object Written : WriteBack

        /** [file] changed on disk since it was read; nothing was written. */
        data class ChangedOnDisk(val file: String) : WriteBack

        /** [written] are on disk, then writing [failed] failed with [reason]; the rest weren't written (no rollback). */
        data class Partial(val written: List<String>, val failed: String, val reason: String) : WriteBack
    }

    /**
     * Write-back of every edited file, all-or-nothing as far as it can be: every hash is checked before anything is
     * written, then each file is written, then the code intelligence is told each written file is saved. A write that
     * fails after others succeeded leaves those on disk (ADR 0003, Consequences); one that fails first throws.
     */
    suspend fun writeBack(): WriteBack {
        val edited = touched.filterValues { it.edited.isNotEmpty() }
        for ((file, it) in edited) {
            if (files.readHashed(file)?.hash != it.base.hash) return WriteBack.ChangedOnDisk(files.display(file))
        }
        val written = mutableListOf<Path>()
        try {
            for ((file, it) in edited) {
                val reason = try {
                    when (files.writeIfUnchanged(file, it.base.hash, it.current)) {
                        WriteResult.Written -> null
                        WriteResult.ChangedOnDisk -> if (written.isEmpty()) return WriteBack.ChangedOnDisk(files.display(file)) else CHANGED_ON_DISK
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Before anything is written, a failed write is an ordinary failure: nothing to report as partial.
                    if (written.isEmpty()) throw e
                    e.message ?: e.toString()
                }
                if (reason != null) return WriteBack.Partial(written.map(files::display), files.display(file), reason)
                written.add(file)
            }
            return WriteBack.Written
        } finally {
            written.forEach { session.writtenBack(it, touched.getValue(it).current) }
        }
    }

    private companion object {
        const val MAX_WARNINGS = 5
        const val CHANGED_ON_DISK = "changed on disk since read"
    }
}
