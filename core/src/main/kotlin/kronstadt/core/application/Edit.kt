package kronstadt.core.application

import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.domain.Validation
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import kronstadt.core.port.HashedDocument
import kronstadt.core.port.WriteResult
import java.nio.file.Path
import kotlin.time.Duration

/** A single step inside an Edit, aimed at one Symbol Path. */
public sealed interface Operation

/** Replaces the whole Symbol Span of [symbol] with [code] (ADR 0004); nothing of the old span is kept. */
public data class Replace(val file: String?, val symbol: String, val code: String) : Operation

/**
 * Use case behind `kt_edit`: applies an Operation in a Shadow Workspace, validates it and writes it back (ADR 0003).
 */
public class Edit(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
    /** One budget for all of Validation's diagnostics: before and after the change. */
    private val validationTimeout: Duration = Validation.DEFAULT_TIMEOUT,
    private val writeLock: WriteLock = WriteLock(),
) {
    private val resolver = SymbolResolver(files, intelligence, readinessTimeout)

    public suspend operator fun invoke(operation: Replace): EditResult {
        val failed = { failure: OperationFailure -> EditResult.OperationFailed(0, failure) }
        val symbol = operation.symbol
        val symbolPath = SymbolPath.parse(symbol).getOrElse { return failed(OperationFailure.InvalidPath(symbol, it.message.orEmpty())) }
        if (operation.file != null && symbolPath.packageName != null) {
            return failed(OperationFailure.InvalidPath(symbol, "a package prefix is only for paths without a file; drop it or the file"))
        }
        return writeLock.withLock {
            val readiness = intelligence.awaitReady(readinessTimeout)
            if (readiness is Readiness.NotReady) return@withLock EditResult.NotReady(readiness.phase, readiness.elapsed)
            val file = when (val located = locate(operation.file, symbolPath)) {
                is Located.File -> located.file
                is Located.Failed -> return@withLock failed(located.failure)
                is Located.NotReady -> return@withLock EditResult.NotReady(located.phase, located.elapsed)
            }
            val read = files.readHashed(file) ?: return@withLock failed(OperationFailure.FileNotFound(operation.file ?: files.display(file)))
            ShadowWorkspace.use(intelligence) { shadow -> replace(shadow, file, read, symbol, symbolPath, operation.code) }
        }
    }

    private sealed interface Located {
        data class File(val file: Path) : Located
        data class Failed(val failure: OperationFailure) : Located
        data class NotReady(val phase: String, val elapsed: Duration) : Located
    }

    /**
     * The file the Operation targets. Without one, the Symbol Path is resolved project-wide only to find its file;
     * it is resolved again in the Shadow Workspace, against the text the Edit is based on.
     */
    private suspend fun locate(path: String?, symbolPath: SymbolPath): Located {
        if (path != null) {
            val file = files.resolve(path) ?: return Located.Failed(OperationFailure.OutsideProject(path))
            if (!AnalysedFile.isKotlin(file)) return Located.Failed(OperationFailure.NotKotlin(path))
            return Located.File(file)
        }
        val symbol = symbolPath.toString()
        return when (val resolution = resolver.resolve(null, symbolPath)) {
            is SymbolResolver.Resolution.Found -> Located.File(resolution.file)
            is SymbolResolver.Resolution.Ambiguous -> Located.Failed(OperationFailure.Ambiguous(symbol, null, resolution.candidates))
            is SymbolResolver.Resolution.NotFound -> Located.Failed(OperationFailure.NotFound(symbol, null, resolution.suggestions))
            is SymbolResolver.Resolution.NotReady -> Located.NotReady(resolution.phase, resolution.elapsed)
            is SymbolResolver.Resolution.FileNotFound -> Located.Failed(OperationFailure.FileNotFound(resolution.path))
            is SymbolResolver.Resolution.NotKotlin -> Located.Failed(OperationFailure.NotKotlin(resolution.path))
            is SymbolResolver.Resolution.OutsideProject -> Located.Failed(OperationFailure.OutsideProject(resolution.path))
        }
    }

    private suspend fun replace(
        shadow: ShadowWorkspace,
        file: Path,
        read: HashedDocument,
        symbol: String,
        symbolPath: SymbolPath,
        newCode: String,
    ): EditResult {
        val failed = { failure: OperationFailure -> EditResult.OperationFailed(0, failure) }
        val shown = files.display(file)
        val document = read.document
        val tree = SymbolTree(shadow.symbols(file, document))
        val node = when (val resolution = tree.resolve(symbolPath)) {
            is SymbolTree.Resolution.Found -> resolution.node
            is SymbolTree.Resolution.Ambiguous ->
                return failed(OperationFailure.Ambiguous(symbol, shown, resolution.candidates.map { candidate(tree, it, shown) }))
            is SymbolTree.Resolution.NotFound ->
                return failed(OperationFailure.NotFound(symbol, shown, resolution.suggestions.map { candidate(tree, it, shown) }))
        }
        val canonical = tree.canonicalPath(node).toString()
        val span = when (val computation = SymbolSpan.of(document, node.symbol)) {
            is SymbolSpan.Computation.Computed -> computation.span
            SymbolSpan.Computation.OutOfSync -> return failed(OperationFailure.OutOfSync(canonical, shown))
            is SymbolSpan.Computation.SharedLine -> return failed(OperationFailure.SharedLine(canonical, shown, computation.line + 1))
        }

        val firstLine = document.line(span.firstLine)
        val prefix = firstLine.takeWhile { it == ' ' || it == '\t' }
        val code = FileLayout.of(document).reindent(newCode, prefix)
        val edited = document.replace(TextRange(Position(span.firstLine, 0), Position(span.lastLine, Int.MAX_VALUE)), code)
        val lastNewLine = span.firstLine + code.lines().size - 1

        val editedLines = span.firstLine..lastNewLine
        val (before, after) = withTimeoutOrNull(validationTimeout) {
            shadow.diagnostics(file, document) to shadow.diagnostics(file, edited)
        } ?: return EditResult.ValidationTimedOut
        val editedTree = SymbolTree(shadow.symbols(file, edited))
        val newErrors = Validation.introduced(before, after, Severity.ERROR, editedLines)
        if (newErrors.isNotEmpty()) {
            return EditResult.Rejected(newErrors.map { problem(files.display(file), edited, editedTree, it) })
        }

        when (files.writeIfUnchanged(file, read.hash, edited)) {
            WriteResult.Written -> Unit
            WriteResult.ChangedOnDisk -> return EditResult.ChangedOnDisk(files.display(file))
        }
        shadow.saved(file, edited)
        val resulting = editedTree.nodes.filter { node ->
            val (first, last) = SymbolSpan.lines(edited, node.symbol)
            first >= span.firstLine && last <= lastNewLine
        }
        val topMost = resulting.filter { node -> resulting.none { it.symbol in node.ancestors } }
        val symbols = topMost.map { node ->
            val (first, last) = SymbolSpan.lines(edited, node.symbol)
            EditedSymbol(editedTree.canonicalPath(node).toString(), first + 1, last + 1)
        }
        val newWarnings = Validation.introduced(before, after, Severity.WARNING, editedLines)
        val preexistingErrors = after.count { it.severity == Severity.ERROR } - newErrors.size
        return EditResult.Written(
            listOf(
                EditedFile(
                    file = files.display(file),
                    symbols = symbols,
                    newWarnings = newWarnings.take(MAX_WARNINGS).map { problem(files.display(file), edited, editedTree, it) },
                    moreWarnings = maxOf(newWarnings.size - MAX_WARNINGS, 0),
                    preexistingErrors = preexistingErrors,
                ),
            ),
        )
    }

    private fun candidate(tree: SymbolTree, node: SymbolTree.Node, file: String) = SymbolCandidate(tree.canonicalPath(node).toString(), file)

    /** [diagnostic] in [document], attributed to the innermost Symbol of [tree] whose range holds it. */
    private fun problem(file: String, document: TextDocument, tree: SymbolTree, diagnostic: Diagnostic): Problem {
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

    private companion object {
        const val MAX_WARNINGS = 5
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
)

/** A diagnostic as the agent sees it: where it is ([line] is 1-based), what it says and the offending source line. */
public data class Problem(val file: String, val symbol: String?, val line: Int, val message: String, val source: String)

public sealed interface EditResult {
    public data class Written(val files: List<EditedFile>) : EditResult

    /** Validation found [newErrors] the Edit introduced; nothing was written. */
    public data class Rejected(val newErrors: List<Problem>) : EditResult

    /** [file] changed on disk after the Edit read it; nothing was written. */
    public data class ChangedOnDisk(val file: String) : EditResult

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
    public data class FileNotFound(val path: String) : OperationFailure
    public data class NotKotlin(val path: String) : OperationFailure
    public data class OutsideProject(val path: String) : OperationFailure
}
