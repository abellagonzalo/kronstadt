package kronstadt.core.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.AnalysedFile
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.DeclarationHead
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Position
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.Validation
import kronstadt.core.domain.namePosition
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/** A file an Edit changed: its [base] text as read from disk and its [current] one; [created] if the Edit creates it. */
internal data class ChangedFile(val file: Path, val base: TextDocument, val current: TextDocument, val created: Boolean)

/**
 * Looks for an Edit's Collateral Errors (see [CollateralErrors]) once Validation passed, before Write-back, in the Edit's
 * [session]. Only an Edit that changed the header of a Symbol that existed before it, or that added a direct subtype of a
 * sealed type that existed before it, is checked: the files that used such a Symbol or sealed type before the Edit, other
 * than the changed ones, each once and at most [CollateralErrors.MAX_FILES] in all, get their diagnostics pulled while
 * every changed file is at its base text and again once every one is at its current text, and the errors the Edit added
 * are reported, as Validation finds them. The check has its own [timeout], and neither running out of it nor failing
 * fails the Edit.
 *
 * A header is what the language server shows of a Symbol: its kind, receiver, parameters and type. A new subtype is a
 * class-like Symbol whose path doesn't resolve in its file's base text (or in a file the Edit created), and its direct
 * supertypes are the names after the `:` of its declaration head, resolved by the language server; one that can't be
 * parsed or resolved isn't checked. Changes neither shows go unseen: visibility, the supertypes of an existing Symbol,
 * and an overload that makes calls ambiguous.
 */
internal class CollateralCheck(
    private val files: FileStore,
    private val session: AnalysisSession,
    /** A file analysed with a text, possibly known already. */
    private val analysed: suspend (Path, TextDocument) -> AnalysedFile,
    private val timeout: Duration,
) {
    /** The Collateral Errors of the Edit that made [changed]. */
    suspend fun run(changed: List<ChangedFile>): CollateralErrors =
        try {
            withTimeoutOrNull(timeout) { check(changed) } ?: CollateralErrors(unfinished = CollateralErrors.Unfinished.TimedOut)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CollateralErrors(unfinished = CollateralErrors.Unfinished.Failed(e.message ?: e.toString()))
        }

    private suspend fun check(changed: List<ChangedFile>): CollateralErrors {
        val headersChanged = changed.filterNot { it.created }.flatMap { file ->
            headersChanged(analysed(file.file, file.base), analysed(file.file, file.current))
                .map { Used(file.file, file.base, it.symbol.namePosition(file.base)) }
        }
        val sealedExtended = sealedSupertypesOfNewSymbols(changed)
        if (headersChanged.isEmpty() && sealedExtended.isEmpty()) return CollateralErrors()
        // Every changed file back at its base text: Usages and base diagnostics are of the code as it was before the Edit.
        changed.forEach { session.documentSymbols(it.file, it.base) }
        val changedFiles = changed.map { it.file }.toSet()
        val using = (headersChanged + sealedExtended).distinct()
            .flatMap { session.references(it.file, it.base, it.name) }
            .map { it.file }
            .distinct()
            .filter { it !in changedFiles && AnalysedFile.isKotlin(it) && files.resolve(it.toString()) == it }
            .mapNotNull { file -> files.read(file)?.let { file to it } }
        val checked = using.take(CollateralErrors.MAX_FILES)
        val before = checked.map { (file, document) -> Validation.withoutSpurious(file, session.diagnostics(file, document)) }
        // Analysing every current text is what changes every changed file to it.
        changed.forEach { session.documentSymbols(it.file, it.current) }
        val after = checked.map { (file, document) -> Validation.withoutSpurious(file, session.diagnostics(file, document)) }
        val errors = checked.indices.flatMap { i ->
            val (file, document) = checked[i]
            val introduced = Validation.introduced(before[i], after[i], Severity.ERROR, editedLines = emptyList())
            if (introduced.isEmpty()) return@flatMap emptyList()
            val analysed = analysed(file, document)
            introduced.map { problem(analysed, it) }
        }
        return CollateralErrors(
            errors = errors.take(CollateralErrors.MAX_LISTED),
            moreErrors = maxOf(errors.size - CollateralErrors.MAX_LISTED, 0),
            filesUnchecked = using.size - checked.size,
        )
    }

    /** A Symbol whose Usages before the Edit are checked: its name is at [name] in [base], [file]'s text before the Edit. */
    private data class Used(val file: Path, val base: TextDocument, val name: Position)

    /**
     * The sealed types that existed before the Edit and that a class-like Symbol it added (one whose path doesn't resolve
     * in its file's base text) names as a direct supertype: a `when` over one of them may no longer be exhaustive. A
     * supertype that doesn't resolve to project source is skipped.
     */
    private suspend fun sealedSupertypesOfNewSymbols(changed: List<ChangedFile>): List<Used> {
        val added = changed.flatMap { file ->
            val current = analysed(file.file, file.current)
            val base = if (file.created) null else analysed(file.file, file.base)
            current.nodes
                .filter { it.symbol.kind in CLASS_LIKE && (base == null || base.resolve(current.path(it)) is AnalysedFile.Resolution.NotFound) }
                .flatMap { node -> DeclarationHead.supertypes(file.current, node.symbol).map { file to it } }
        }
        if (added.isEmpty()) return emptyList()
        // Every changed file at its current text, where the added Symbols' supertypes resolve.
        changed.forEach { session.documentSymbols(it.file, it.current) }
        val byFile = changed.associateBy { it.file }
        return added
            .flatMap { (file, name) -> session.definition(file.file, file.current, name) }
            .filterIsInstance<Definition.InSource>()
            .map { it.location }
            .distinct()
            .mapNotNull { location -> sealedBeforeEdit(location, byFile[location.file]) }
    }

    /**
     * The class-like Symbol declared at [location] as it was before the Edit, if it was sealed then; [changed] is its file
     * if the Edit changed it. Null for one the Edit created.
     */
    private suspend fun sealedBeforeEdit(location: SourceLocation, changed: ChangedFile?): Used? {
        if (changed?.created == true) return null
        val file = location.file
        val current = changed?.current ?: files.read(file) ?: return null
        val inCurrent = analysed(file, current)
        val node = inCurrent.nodes.lastOrNull { it.symbol.kind in CLASS_LIKE && location.range.start in it.symbol.selectionRange } ?: return null
        val base = changed?.base ?: current
        val atBase = if (changed == null) node else {
            val inBase = analysed(file, base)
            (inBase.resolve(inCurrent.path(node)) as? AnalysedFile.Resolution.Found)?.node ?: return null
        }
        if (!DeclarationHead.isSealed(base, atBase.symbol)) return null
        return Used(file, base, atBase.symbol.namePosition(base))
    }

    private companion object {
        /**
         * The Symbols of [base] whose header [current] changed: their canonical path no longer resolves (removed or
         * renamed), or what it resolves to differs in kind, receiver, parameters or the type the language server shows.
         * Changes it doesn't show, such as visibility or supertypes, go unseen.
         */
        fun headersChanged(base: AnalysedFile, current: AnalysedFile): List<SymbolTree.Node> = base.nodes.filter { node ->
            when (val resolution = current.resolve(base.path(node))) {
                is AnalysedFile.Resolution.Found -> !sameHeader(node.symbol, resolution.node.symbol)
                is AnalysedFile.Resolution.NotFound -> true
                is AnalysedFile.Resolution.Ambiguous -> resolution.matches.none { sameHeader(node.symbol, it.symbol) }
            }
        }

        fun sameHeader(a: CodeSymbol, b: CodeSymbol) =
            a.kind == b.kind && a.receiver == b.receiver && a.parameterTypes == b.parameterTypes && a.type == b.type

        /** The kinds of Symbol that can extend a sealed type, and be one. */
        val CLASS_LIKE = setOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.OBJECT, SymbolKind.ENUM)
    }
}
