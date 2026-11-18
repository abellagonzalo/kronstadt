package kronstadt.core.application

import kronstadt.core.domain.FileChange
import kronstadt.core.domain.FileHeader
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.Validation
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Use case behind `kt_rename_symbol`: renames one Symbol and every Usage of it, across files and modules, through
 * the code intelligence. Its changes are applied in a Shadow Workspace, validated once and written back as a whole,
 * like an Edit's (ADR 0003); a rename can't be part of an Edit.
 */
public class RenameSymbol(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
    /** One budget for all of Validation's diagnostics, across every changed file. */
    private val validationTimeout: Duration = Validation.DEFAULT_TIMEOUT,
    private val writeLock: WriteLock = WriteLock(),
) {
    private val resolver = SymbolResolver(files, intelligence, readinessTimeout)

    /** Renames the Symbol at [symbol] to [newName]; [symbol] is resolved in [file], or project-wide without one. */
    public suspend operator fun invoke(symbol: String, newName: String, file: String? = null): RenameResult {
        val symbolPath = SymbolPath.parse(symbol)
            .getOrElse { return RenameResult.Unresolved(OperationFailure.InvalidPath(symbol, it.message.orEmpty())) }
        if (file != null && symbolPath.packageName != null) {
            return RenameResult.Unresolved(
                OperationFailure.InvalidPath(symbol, "a package prefix is only for paths without a file; drop it or the file"),
            )
        }
        if (!IDENTIFIER.matches(newName)) return RenameResult.InvalidName(newName)
        return writeLock.withLock {
            val readiness = intelligence.awaitReady(readinessTimeout)
            if (readiness is Readiness.NotReady) return@withLock RenameResult.NotReady(readiness.phase, readiness.elapsed)
            intelligence.session { session -> rename(session, symbol, symbolPath, file, newName) }
        }
    }

    private suspend fun rename(session: AnalysisSession, symbol: String, symbolPath: SymbolPath, path: String?, newName: String): RenameResult {
        val target = when (val located = locate(session, symbolPath, path)) {
            is Located.File -> located.file
            is Located.Failed -> return located.result
        }
        val shadow = ShadowWorkspace(files, session, resolver, validationTimeout)
        val shown = files.display(target)
        val declaration = when (val resolution = resolver.resolveIn(shadow, target, symbolPath)) {
            null -> return RenameResult.Unresolved(OperationFailure.FileNotFound(path ?: shown))
            is SymbolResolver.Resolution.Found -> resolution
            is SymbolResolver.Resolution.Ambiguous -> return RenameResult.Unresolved(OperationFailure.Ambiguous(symbol, shown, resolution.candidates))
            is SymbolResolver.Resolution.NotFound -> return RenameResult.Unresolved(OperationFailure.NotFound(symbol, shown, resolution.suggestions))
            else -> error("in-file resolution reported $resolution")
        }
        val node = declaration.node
        val canonical = declaration.tree.canonicalPath(node).toString()
        if (node.symbol.kind == SymbolKind.CONSTRUCTOR) return RenameResult.CannotRename(canonical, CONSTRUCTOR)
        val name = node.symbol.selectionRange
        val preparation = session.prepareRename(target, declaration.document, name.start)
        if (preparation is RenamePreparation.Refused) return RenameResult.CannotRename(canonical, preparation.reason)
        val changes = when (val renamed = session.rename(target, declaration.document, name.start, newName)) {
            is Rename.Changes -> renamed.changes
            is Rename.Refused -> return RenameResult.CannotRename(canonical, renamed.reason)
        }
        val touchedPaths = changes.flatMap { change ->
            when (change) {
                is FileChange.Edited -> listOf(change.file)
                is FileChange.Moved -> listOf(change.from, change.to)
            }
        }
        touchedPaths.firstOrNull { files.resolve(it.toString()) != it }
            ?.let { return RenameResult.CannotRename(canonical, "it would change $it, outside the project") }
        val counts = linkedMapOf(target to Counts())
        val before = mutableMapOf<Path, TextDocument>()
        // A moved file stays at its old path in the Shadow Workspace until Write-back; edits may name either path.
        val movedFrom = mutableMapOf<Path, Path>()
        var fileRenamed: FileRenamed? = null
        for (change in changes) {
            when (change) {
                is FileChange.Edited -> {
                    val file = movedFrom[change.file] ?: change.file
                    val document = shadow.current(file)
                        ?: return RenameResult.CannotRename(canonical, "it would change ${files.display(file)}, which doesn't exist")
                    before.putIfAbsent(file, document)
                    shadow.applyEdits(file, change.edits)
                    counts.getOrPut(file) { Counts() }.add(document, change.edits) { edit ->
                        // Edits are minimal, e.g. an insertion right after the name: the declaration's lies within its name, ends included.
                        file == target && edit.range.start >= name.start && edit.range.end <= name.end
                    }
                }
                is FileChange.Moved -> {
                    if (files.read(change.to) != null) return RenameResult.TargetExists(files.display(change.from), files.display(change.to))
                    if (!shadow.move(change.from, change.to)) {
                        return RenameResult.CannotRename(canonical, "it would rename ${files.display(change.from)}, which doesn't exist")
                    }
                    movedFrom[change.to] = change.from
                    fileRenamed = FileRenamed(files.display(change.from), files.display(change.to))
                }
            }
        }
        val oldName = wholeWord(node.symbol.name)
        return when (val validated = shadow.validate { it.replace(oldName, newName) }) {
            is ShadowWorkspace.Validated.Passed -> when (val writeBack = shadow.writeBack()) {
                ShadowWorkspace.WriteBack.Written -> {
                    val renamedFiles = counts.entries.sortedWith(compareBy({ it.key != target }, { files.display(it.key) }))
                        .map { (file, counted) ->
                            val report = validated.files.single { it.file == files.display(file) }
                            RenamedFile(
                                files.display(file), counted.usages, counted.imports,
                                report.newWarnings, report.moreWarnings, report.preexistingErrors,
                            )
                        }
                    RenameResult.Renamed(
                        newPath(shadow, target, node, newName),
                        renamedFiles,
                        fileRenamed,
                        before.flatMap { (file, document) ->
                            removedImports(document, checkNotNull(shadow.current(file)), node.symbol.name, newName)
                                .map { RemovedImport(files.display(file), it) }
                        },
                    )
                }
                is ShadowWorkspace.WriteBack.ChangedOnDisk -> RenameResult.ChangedOnDisk(writeBack.file)
                is ShadowWorkspace.WriteBack.TargetExists -> RenameResult.TargetExists(writeBack.file, writeBack.target)
                is ShadowWorkspace.WriteBack.Partial -> RenameResult.PartiallyWritten(writeBack.written, writeBack.failed, writeBack.reason)
            }
            is ShadowWorkspace.Validated.Rejected -> RenameResult.Rejected(validated.newErrors)
            ShadowWorkspace.Validated.TimedOut -> RenameResult.ValidationTimedOut
        }
    }

    /** Imports in [before] that [after] lacks, other than those it has with [oldName] renamed to [newName]. */
    private fun removedImports(before: TextDocument, after: TextDocument, oldName: String, newName: String): List<String> {
        val kept = FileHeader.of(after.text).imports.map { it.directive.text }.toSet()
        val renamed = wholeWord(oldName)
        return FileHeader.of(before.text).imports.map { it.directive.text }
            .filter { it !in kept && it.replace(renamed, newName) !in kept }
    }

    private fun wholeWord(name: String) = Regex("\\b${Regex.escape(name)}\\b")

    /** The renamed Symbol's canonical path in [file]'s renamed text: the same chain and kind, with [newName] last. */
    private suspend fun newPath(shadow: ShadowWorkspace, file: Path, node: SymbolTree.Node, newName: String): String? {
        val analysed = shadow.analysed(file) ?: return null
        val names = node.chain.dropLast(1).map { it.name } + newName
        val renamed = analysed.tree.nodes.singleOrNull {
            it.chain.map { symbol -> symbol.name } == names && it.symbol.kind == node.symbol.kind && it.symbol.detail == node.symbol.detail
        } ?: return null
        return analysed.tree.canonicalPath(renamed).toString()
    }

    /** What a rename changed in one file: its Usages renamed, imports of the Symbol apart. */
    private class Counts {
        var usages = 0
        var imports = 0

        /**
         * Counts [edits] to [document]: a Usage is renamed within one line, an import line counts apart, and an edit
         * across lines removes an import rather than renaming anything. The [declaration]'s own edit isn't counted.
         */
        fun add(document: TextDocument, edits: List<TextEdit>, declaration: (TextEdit) -> Boolean) {
            for (edit in edits) {
                if (edit.range.start.line != edit.range.end.line || declaration(edit)) continue
                if (document.line(edit.range.start.line).trimStart().startsWith("import ")) imports++ else usages++
            }
        }
    }

    private sealed interface Located {
        data class File(val file: Path) : Located
        data class Failed(val result: RenameResult) : Located
    }

    /** The file the Symbol is declared in: [path], or without one where the project-wide lookup finds it. */
    private suspend fun locate(session: AnalysisSession, symbolPath: SymbolPath, path: String?): Located {
        fun failed(failure: OperationFailure) = Located.Failed(RenameResult.Unresolved(failure))
        if (path != null) {
            val file = files.resolve(path) ?: return failed(OperationFailure.OutsideProject(path))
            if (!AnalysedFile.isKotlin(file)) return failed(OperationFailure.NotKotlin(path))
            return Located.File(file)
        }
        val symbol = symbolPath.toString()
        return when (val resolution = resolver.resolve(session, null, symbolPath)) {
            is SymbolResolver.Resolution.Found -> Located.File(resolution.file)
            is SymbolResolver.Resolution.Ambiguous -> failed(OperationFailure.Ambiguous(symbol, null, resolution.candidates))
            is SymbolResolver.Resolution.NotFound -> failed(OperationFailure.NotFound(symbol, null, resolution.suggestions))
            is SymbolResolver.Resolution.NotReady -> Located.Failed(RenameResult.NotReady(resolution.phase, resolution.elapsed))
            is SymbolResolver.Resolution.FileNotFound -> failed(OperationFailure.FileNotFound(resolution.path))
            is SymbolResolver.Resolution.NotKotlin -> failed(OperationFailure.NotKotlin(resolution.path))
            is SymbolResolver.Resolution.OutsideProject -> failed(OperationFailure.OutsideProject(resolution.path))
        }
    }

    private companion object {
        /** A plain Kotlin identifier, or any name in backticks. */
        val IDENTIFIER = Regex("[\\p{L}_][\\p{L}\\p{Nd}_]*|`[^`\\r\\n]+`")
        const val CONSTRUCTOR = "a constructor has no name of its own; rename its class"
    }
}

/**
 * A file a rename changed: the [usages] of the Symbol it renamed there and, counted apart, its [imports] of it (not
 * its declaration), with new warnings and the errors it already had, as for an Edit.
 */
public data class RenamedFile(
    val file: String,
    val usages: Int,
    val imports: Int = 0,
    val newWarnings: List<Problem> = emptyList(),
    val moreWarnings: Int = 0,
    val preexistingErrors: Int = 0,
)

/** The file [from] was renamed to [to], both as shown to the agent. */
public data class FileRenamed(val from: String, val to: String)

/** An import kotlin-lsp removed from [file] while renaming, e.g. `a.b.C`. */
public data class RemovedImport(val file: String, val import: String)

/** The outcome of a rename: written, or why nothing was (except [PartiallyWritten]). */
public sealed interface RenameResult {
    /**
     * Written: the declaring file first, then the others; [fileRenamed] if a class named like its file took the file
     * along, and the unused imports the code intelligence removed. [newPath] is the Symbol's canonical path now; null
     * if it can't be told apart in the renamed file.
     */
    public data class Renamed(
        val newPath: String?,
        val files: List<RenamedFile>,
        val fileRenamed: FileRenamed? = null,
        val importsRemoved: List<RemovedImport> = emptyList(),
    ) : RenameResult

    /** The Symbol Path or file doesn't name exactly one Symbol; the same failures as for a kt_edit Operation's target. */
    public data class Unresolved(val failure: OperationFailure) : RenameResult

    /** [name] isn't a Kotlin identifier (plain, or in backticks); nothing was asked or written. */
    public data class InvalidName(val name: String) : RenameResult

    /** [symbol] (its canonical path) can't be renamed, for [reason]: the code intelligence's, or Kronstadt's own. */
    public data class CannotRename(val symbol: String, val reason: String) : RenameResult

    /** The rename would move [file] onto [target], which exists; nothing was written. */
    public data class TargetExists(val file: String, val target: String) : RenameResult

    /** Validation found [newErrors] in the changed files; nothing was written. */
    public data class Rejected(val newErrors: List<Problem>) : RenameResult

    /** [file] changed on disk since it was read; nothing was written. */
    public data class ChangedOnDisk(val file: String) : RenameResult

    /** [written] are on disk, then writing [failed] failed with [reason]; the rest weren't written (no rollback). */
    public data class PartiallyWritten(val written: List<String>, val failed: String, val reason: String) : RenameResult

    /** Validation's diagnostics took longer than its timeout; nothing was written. */
    public data object ValidationTimedOut : RenameResult

    /** The index was still incomplete after waiting [elapsed]; nothing was written. */
    public data class NotReady(val phase: String, val elapsed: Duration) : RenameResult
}
