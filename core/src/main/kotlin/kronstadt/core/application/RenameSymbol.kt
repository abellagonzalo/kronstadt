package kronstadt.core.application

import kronstadt.core.domain.FileChange
import kronstadt.core.domain.FileHeader
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.TextRange
import kronstadt.core.domain.Validation
import kronstadt.core.domain.addingImports
import kronstadt.core.domain.namePosition
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Use case behind renaming a Symbol: renames one Symbol and every Usage of it, across files and modules, through
 * the code intelligence. Its changes are applied in a Shadow Workspace, validated once and written back as a whole,
 * like an Edit's (ADR 0003); a rename can't be part of an Edit.
 */
public class RenameSymbol(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
    /** One budget for all of Validation's diagnostics, across every changed file. */
    private val validationTimeout: Duration = Validation.DEFAULT_TIMEOUT,
    private val writeCalls: WriteCalls = WriteCalls(),
) {
    private val resolver = SymbolResolver(files, intelligence)

    /** Renames the Symbol at [symbol] to [newName]; [symbol] is resolved in [file], or project-wide without one. */
    public suspend operator fun invoke(symbol: String, newName: String, file: String? = null): RenameResult {
        // A rename drops the last Rejected Edit, whatever the outcome, so even a malformed call goes through write calls.
        return writeCalls.rename {
            val parsed = SymbolResolver.parse(file, symbol).getOrElse { return@rename RenameResult.LookupFailed(it) }
            if (!IDENTIFIER.matches(newName)) return@rename RenameResult.InvalidName(newName)
            val readiness = intelligence.awaitReady(readinessTimeout)
            if (readiness is Readiness.NotReady) return@rename RenameResult.NotReady(readiness)
            intelligence.session { session -> rename(session, parsed, newName) }
        }
    }

    private suspend fun rename(session: AnalysisSession, parsed: ParsedLookup, newName: String): RenameResult {
        val shadow = ShadowWorkspace(files, session, resolver, validationTimeout)
        val declaration = resolver.resolve(session, parsed, shadow).getOrElse { return RenameResult.LookupFailed(it) }
        val target = declaration.analysed.file
        val node = declaration.node
        val canonical = declaration.canonical
        if (node.symbol.kind == SymbolKind.CONSTRUCTOR) return RenameResult.CannotRename(canonical, CONSTRUCTOR)
        val name = node.symbol.selectionRange
        val position = node.symbol.namePosition(declaration.analysed.document)
        val preparation = session.prepareRename(target, declaration.analysed.document, position)
        if (preparation is RenamePreparation.Refused) return RenameResult.CannotRename(canonical, preparation.reason)
        val changes = when (val renamed = session.rename(target, declaration.analysed.document, position, newName)) {
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
        val oldWord = nameAsWritten(node.symbol.name)
        val applied = when (val applying = applyChanges(shadow, changes, target, name, oldWord, canonical)) {
            is Applying.Applied -> applying
            is Applying.Failed -> return applying.result
        }
        val restored = restoreDroppedImports(shadow, applied.before, node.symbol.name, newName)
        val removed = shadow.removeImportsMadeUnused(session, validationTimeout) { file, suspects -> suspects.intersect(restored[file].orEmpty()) }
        return when (val validated = shadow.validate { it.replace(oldWord, newName) }) {
            is ShadowWorkspace.Validated.Passed -> shadow.writeBack()?.let { RenameResult.WriteBackFailed(it) }
                ?: renamed(shadow, target, node, newName, applied, validated.files, removed)
            is ShadowWorkspace.Validated.Failed -> RenameResult.ValidationFailed(validated.failure)
        }
    }

    /** kotlin-lsp's changes, applied to the Shadow Workspace, or why they can't be. */
    private sealed interface Applying {
        /**
         * Applied: what each changed file was [before] its first edit, the [counts] of each, the declaring file first, and
         * the file renamed along with the Symbol, if any.
         */
        class Applied(val before: Map<Path, TextDocument>, val counts: Map<Path, Counts>, val fileRenamed: FileRenamed?) : Applying
        class Failed(val result: RenameResult) : Applying
    }

    /**
     * Applies [changes] to [shadow] in order, counting each file's Usages of [oldWord] apart from [name], the declaration
     * in [target]. A moved file stays at its old path in the Shadow Workspace until Write-back, so later edits that name
     * its new path go to its old one.
     */
    private suspend fun applyChanges(
        shadow: ShadowWorkspace,
        changes: List<FileChange>,
        target: Path,
        name: TextRange,
        oldWord: Regex,
        canonical: String,
    ): Applying {
        val counts = linkedMapOf(target to Counts())
        val before = mutableMapOf<Path, TextDocument>()
        val movedFrom = mutableMapOf<Path, Path>()
        var fileRenamed: FileRenamed? = null
        for (change in changes) {
            when (change) {
                is FileChange.Edited -> {
                    val file = movedFrom[change.file] ?: change.file
                    val document = shadow.current(file)
                        ?: return Applying.Failed(RenameResult.CannotRename(canonical, "it would change ${files.display(file)}, which doesn't exist"))
                    before.putIfAbsent(file, document)
                    shadow.applyEdits(file, change.edits)
                    counts.getOrPut(file) { Counts() }.add(document, change.edits, oldWord, declaration = name.takeIf { file == target })
                }
                is FileChange.Moved -> {
                    if (files.read(change.to) != null) {
                        return Applying.Failed(RenameResult.WriteBackFailed(WriteBackFailure.TargetExists(files.display(change.from), files.display(change.to))))
                    }
                    if (!shadow.move(change.from, change.to)) {
                        return Applying.Failed(RenameResult.CannotRename(canonical, "it would rename ${files.display(change.from)}, which doesn't exist"))
                    }
                    movedFrom[change.to] = change.from
                    fileRenamed = FileRenamed(files.display(change.from), files.display(change.to))
                }
            }
        }
        return Applying.Applied(before, counts, fileRenamed)
    }

    /**
     * kotlin-lsp's rename also drops imports that were already unused. They go back, and only those the rename made unused
     * go again, as an Edit decides it (ADR 0004). Returns those put back in each file of [before].
     */
    private suspend fun restoreDroppedImports(
        shadow: ShadowWorkspace,
        before: Map<Path, TextDocument>,
        oldName: String,
        newName: String,
    ): Map<Path, Set<ImportDirective>> = before.mapNotNull { (file, document) ->
        val current = checkNotNull(shadow.current(file))
        val dropped = removedImports(document, current, oldName, newName)
        current.addingImports(dropped)?.let { shadow.replaceLines(file, it.lines, it.newLines) } ?: return@mapNotNull null
        file to dropped.toSet()
    }.toMap()

    /**
     * The written rename: each changed file's [applied] counts with its [reports] from Validation, the declaring file
     * first, the imports [removed] from each file, the file renamed and the Symbol's new path.
     */
    private suspend fun renamed(
        shadow: ShadowWorkspace,
        target: Path,
        node: SymbolTree.Node,
        newName: String,
        applied: Applying.Applied,
        reports: List<EditedFile>,
        removed: Map<Path, List<String>>,
    ): RenameResult.Renamed {
        val renamedFiles = applied.counts.entries.sortedWith(compareBy({ it.key != target }, { files.display(it.key) }))
            .map { (file, counted) ->
                val report = reports.single { it.file == files.display(file) }
                RenamedFile(
                    files.display(file), counted.usages, counted.imports,
                    report.newWarnings, report.moreWarnings, report.preexistingErrors, report.morePreexistingErrors,
                )
            }
        return RenameResult.Renamed(
            newPath(shadow, target, node, newName),
            renamedFiles,
            applied.fileRenamed,
            removed.flatMap { (file, imports) -> imports.map { RemovedImport(files.display(file), it) } },
        )
    }

    /** Imports in [before] that [after] lacks, other than those it has with [oldName] renamed to [newName]. */
    private fun removedImports(before: TextDocument, after: TextDocument, oldName: String, newName: String): List<ImportDirective> {
        val kept = FileHeader.of(after.text).imports.map { it.directive.text }.toSet()
        val renamed = nameAsWritten(oldName)
        return FileHeader.of(before.text).imports.map { it.directive }
            .filter { it.text !in kept && it.text.replace(renamed, newName) !in kept }
    }

    /**
     * The name [name] as written in source: in backticks, and also bare if it's a plain identifier. Each place it matches
     * is replaced by the new name as the agent gave it, which is how kotlin-lsp writes it too.
     */
    private fun nameAsWritten(name: String): Regex {
        val quoted = Regex.escape("`$name`")
        return Regex(if (PLAIN.matches(name)) "$quoted|\\b${Regex.escape(name)}\\b" else quoted)
    }

    /** The renamed Symbol's canonical path in [file]'s renamed text: the same chain and kind, with [newName] last. */
    private suspend fun newPath(shadow: ShadowWorkspace, file: Path, node: SymbolTree.Node, newName: String): String? {
        val analysed = shadow.analysed(file) ?: return null
        val names = node.chain.dropLast(1).map { it.name } + newName.removeSurrounding("`")
        val renamed = analysed.nodes.singleOrNull {
            it.chain.map { symbol -> symbol.name } == names && it.symbol.kind == node.symbol.kind && it.symbol.receiver == node.symbol.receiver && it.symbol.type == node.symbol.type
        } ?: return null
        return analysed.path(renamed).toString()
    }

    /** What a rename changed in one file: its Usages renamed, imports of the Symbol apart. */
    private class Counts {
        var usages = 0
        var imports = 0

        /**
         * Counts the places [oldName], the name as written (see [nameAsWritten]), appears in [document] that [edits] change, however the code
         * intelligence splits them: kotlin-lsp sends minimal diffs, often several to one name (#85). One on an import line
         * counts that import once, any other is a Usage. The one within [declaration], the Symbol's own name, isn't counted.
         */
        fun add(document: TextDocument, edits: List<TextEdit>, oldName: Regex, declaration: TextRange?) {
            val importLines = mutableSetOf<Int>()
            for (match in oldName.findAll(document.text)) {
                val start = document.positionOf(match.range.first)
                val end = document.positionOf(match.range.last + 1)
                if (declaration != null && start >= declaration.start && end <= declaration.end) continue
                if (edits.none { it.changes(start, end) }) continue
                if (document.line(start.line).trimStart().startsWith("import ")) importLines += start.line else usages++
            }
            imports += importLines.size
        }

        /** Whether this edit changes the text from [start] to [end]: overlaps it, or inserts at it, ends included. */
        private fun TextEdit.changes(start: Position, end: Position): Boolean =
            if (range.start == range.end) range.start in start..end else range.start < end && range.end > start
    }

    private companion object {
        /** A plain Kotlin identifier. */
        val PLAIN = Regex("[\\p{L}_][\\p{L}\\p{Nd}_]*")

        /** A plain Kotlin identifier, or any name in backticks. */
        val IDENTIFIER = Regex("${PLAIN.pattern}|`[^`\\r\\n]+`")
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
    val preexistingErrors: List<Problem> = emptyList(),
    val morePreexistingErrors: Int = 0,
)

/** The file [from] was renamed to [to], both as shown to the agent. */
public data class FileRenamed(val from: String, val to: String)

/** An import kotlin-lsp removed from [file] while renaming, e.g. `a.b.C`. */
public data class RemovedImport(val file: String, val import: String)

/** The outcome of a rename: written, or why nothing was (except [WriteBackFailure.PartiallyWritten]). */
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

    /** The Symbol Path or file doesn't name exactly one Symbol. */
    public data class LookupFailed(val failure: LookupFailure) : RenameResult

    /** [name] isn't a Kotlin identifier (plain, or in backticks); nothing was asked or written. */
    public data class InvalidName(val name: String) : RenameResult

    /** [symbol] (its canonical path) can't be renamed, for [reason]: the code intelligence's, or Kronstadt's own. */
    public data class CannotRename(val symbol: String, val reason: String) : RenameResult

    /** Validation let nothing be written, for [failure]. */
    public data class ValidationFailed(val failure: ValidationFailure) : RenameResult

    /** Write-back didn't write every file, for [failure]; a file moved onto one that exists is one. */
    public data class WriteBackFailed(val failure: WriteBackFailure) : RenameResult

    /** The index was still incomplete after waiting; nothing was written. */
    public data class NotReady(val readiness: Readiness.NotReady) : RenameResult
}
