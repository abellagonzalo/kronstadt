package kronstadt.core.application

import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.Validation
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/** A single step inside an Edit, aimed at one Symbol Path. */
public sealed interface Operation

/** Replaces the whole Symbol Span of [symbol] with [code] (ADR 0004); nothing of the old span is kept. */
public data class Replace(val file: String?, val symbol: String, val code: String) : Operation

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

    public suspend operator fun invoke(operation: Replace): EditResult = invoke(listOf(operation))

    /** Several Operations, applied in order; `kt_edit` passes exactly one until it takes a batch (#26). */
    internal suspend operator fun invoke(operations: List<Replace>): EditResult {
        val paths = operations.mapIndexed { index, operation ->
            val symbol = operation.symbol
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
        return writeLock.withLock {
            val readiness = intelligence.awaitReady(readinessTimeout)
            if (readiness is Readiness.NotReady) return@withLock EditResult.NotReady(readiness.phase, readiness.elapsed)
            intelligence.session { session ->
                val shadow = ShadowWorkspace(files, session, resolver, validationTimeout)
                operations.zip(paths).forEachIndexed { index, (operation, symbolPath) ->
                    when (val applied = apply(session, shadow, operation, symbolPath)) {
                        Applied.Done -> Unit
                        is Applied.Failed -> return@session EditResult.OperationFailed(index, applied.failure)
                        is Applied.NotReady -> return@session EditResult.NotReady(applied.phase, applied.elapsed)
                    }
                }
                when (val validated = shadow.validate()) {
                    is ShadowWorkspace.Validated.Passed -> when (val writeBack = shadow.writeBack()) {
                        ShadowWorkspace.WriteBack.Written -> EditResult.Written(validated.files)
                        is ShadowWorkspace.WriteBack.ChangedOnDisk -> EditResult.ChangedOnDisk(writeBack.file)
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
        data object Done : Applied
        data class Failed(val failure: OperationFailure) : Applied
        data class NotReady(val phase: String, val elapsed: Duration) : Applied
    }

    /** Resolves [operation]'s Symbol against the Shadow Workspace's current text and replaces its span, re-indented. */
    private suspend fun apply(session: AnalysisSession, shadow: ShadowWorkspace, operation: Replace, symbolPath: SymbolPath): Applied {
        val symbol = operation.symbol
        val file = when (val located = locate(session, operation.file, symbolPath)) {
            is Located.File -> located.file
            is Located.Failed -> return Applied.Failed(located.failure)
            is Located.NotReady -> return Applied.NotReady(located.phase, located.elapsed)
        }
        val shown = files.display(file)
        val found = when (val lookup = shadow.resolve(file, symbolPath)) {
            is ShadowWorkspace.Lookup.Found -> lookup
            is ShadowWorkspace.Lookup.Ambiguous -> return Applied.Failed(OperationFailure.Ambiguous(symbol, shown, lookup.candidates))
            is ShadowWorkspace.Lookup.NotFound -> return Applied.Failed(OperationFailure.NotFound(symbol, shown, lookup.suggestions))
            is ShadowWorkspace.Lookup.OutOfSync -> return Applied.Failed(OperationFailure.OutOfSync(lookup.canonical, shown))
            is ShadowWorkspace.Lookup.SharedLine ->
                return Applied.Failed(OperationFailure.SharedLine(lookup.canonical, shown, lookup.line + 1))
            ShadowWorkspace.Lookup.FileNotFound -> return Applied.Failed(OperationFailure.FileNotFound(operation.file ?: shown))
        }
        val span = found.span
        val prefix = found.document.line(span.firstLine).takeWhile { it == ' ' || it == '\t' }
        shadow.replaceSpan(file, span.firstLine..span.lastLine, FileLayout.of(found.document).reindent(operation.code, prefix))
        return Applied.Done
    }

    private sealed interface Located {
        data class File(val file: Path) : Located
        data class Failed(val failure: OperationFailure) : Located
        data class NotReady(val phase: String, val elapsed: Duration) : Located
    }

    /**
     * The file the Operation targets. Without one, the Symbol Path is resolved project-wide only to find its file;
     * it is resolved again in the Shadow Workspace, against the text the earlier Operations left.
     */
    private suspend fun locate(session: AnalysisSession, path: String?, symbolPath: SymbolPath): Located {
        if (path != null) {
            val file = files.resolve(path) ?: return Located.Failed(OperationFailure.OutsideProject(path))
            if (!AnalysedFile.isKotlin(file)) return Located.Failed(OperationFailure.NotKotlin(path))
            return Located.File(file)
        }
        val symbol = symbolPath.toString()
        return when (val resolution = resolver.resolve(session, null, symbolPath)) {
            is SymbolResolver.Resolution.Found -> Located.File(resolution.file)
            is SymbolResolver.Resolution.Ambiguous -> Located.Failed(OperationFailure.Ambiguous(symbol, null, resolution.candidates))
            is SymbolResolver.Resolution.NotFound -> Located.Failed(OperationFailure.NotFound(symbol, null, resolution.suggestions))
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
    public data class FileNotFound(val path: String) : OperationFailure
    public data class NotKotlin(val path: String) : OperationFailure
    public data class OutsideProject(val path: String) : OperationFailure
}
