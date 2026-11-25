package kronstadt.core.application

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.AnalysedFile
import kronstadt.core.domain.CodeSymbol
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
 * [session]. Only an Edit that changed the header of a Symbol that existed before it is checked: the files that used
 * such a Symbol before the Edit, other than the changed ones, get their diagnostics pulled while every changed file is at
 * its base text and again once every one is at its current text, and the errors the Edit added are reported, as
 * Validation finds them. The check has its own [timeout], and neither running out of it nor failing fails the Edit.
 *
 * A header is what the language server shows of a Symbol: its kind, receiver, parameters and type. Changes it doesn't
 * show go unseen: visibility, supertypes, an overload that makes calls ambiguous, and a new subtype of a sealed type
 * that leaves a `when` over it non-exhaustive (no Symbol of the sealed type's file changed its header).
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
            headersChanged(analysed(file.file, file.base), analysed(file.file, file.current)).map { file to it }
        }
        if (headersChanged.isEmpty()) return CollateralErrors()
        // Every changed file back at its base text: Usages and base diagnostics are of the code as it was before the Edit.
        changed.forEach { session.documentSymbols(it.file, it.base) }
        val changedFiles = changed.map { it.file }.toSet()
        val using = headersChanged
            .flatMap { (file, node) -> session.references(file.file, file.base, node.symbol.namePosition(file.base)) }
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
    }
}
