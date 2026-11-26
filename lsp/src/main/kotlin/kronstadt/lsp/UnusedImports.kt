package kronstadt.lsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.Severity
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.Validation
import kronstadt.core.domain.imports
import kronstadt.core.domain.removingImports
import kronstadt.core.port.AnalysisSession
import java.nio.file.Path
import kotlin.time.Duration

/**
 * What [organized], a file's text after kotlin-lsp organized its imports, tells of the file's own imports: those it
 * [dropped], found unused, and those it left [undecided]: explicit imports it dropped only because a star import of
 * their package, new in [organized], now brings them in. kotlin-lsp collapses a package into a star once 5 of its names
 * are used, whether or not one of them still is (#81). Every other import is kept: one still in [organized], and a star
 * import whose package it newly imports by name.
 */
internal class OrganizedImports(val dropped: Set<ImportDirective>, val undecided: Set<ImportDirective>)

/** What [organized], this file's text after kotlin-lsp organized its imports, tells of its imports. */
internal fun TextDocument.importsOrganizedIn(organized: TextDocument): OrganizedImports {
    val own = imports
    val left = organized.imports.toSet()
    val (newStars, newNames) = left.filter { it !in own }.partition { it.isStar }
    val gone = own.filter { directive -> directive !in left && newNames.none { it.isCoveredBy(directive) } }
    val (undecided, dropped) = gone.partition { directive -> newStars.any(directive::isCoveredBy) }
    return OrganizedImports(dropped.toSet(), undecided.toSet())
}

/**
 * Those of [among], imports of [document] (the content of [file]), that [organized], kotlin-lsp's organize-imports
 * answer for it, shows unused (ADR 0004): those it dropped, and those it left undecided that removing adds no error
 * (see [removableAmong]). [budget] bounds only that last check; when it runs out, the undecided ones count as used.
 */
internal suspend fun AnalysisSession.unusedPerOrganizeImports(
    file: Path,
    document: TextDocument,
    organized: OrganizedImports,
    among: Set<ImportDirective>,
    budget: Duration,
): Set<ImportDirective> =
    organized.dropped.intersect(among) + removableAmong(file, document, organized.undecided.intersect(among), budget)

/**
 * Those of [suspects], imports of [document] (the content of [file]), that removing from it adds no error. All of
 * them are tried together first, then each alone. Without an answer within [budget], or with a failed one, an import
 * counts as used.
 */
private suspend fun AnalysisSession.removableAmong(
    file: Path,
    document: TextDocument,
    suspects: Set<ImportDirective>,
    budget: Duration,
): Set<ImportDirective> {
    if (suspects.isEmpty() || !budget.isPositive()) return emptySet()
    val unused = try {
        withTimeoutOrNull(budget) {
            val errors = diagnostics(file, document)
            suspend fun removable(drop: Set<ImportDirective>): Set<ImportDirective> {
                val removal = document.removingImports(drop) ?: return emptySet()
                val without = document.replaceLines(removal.splice.lines, removal.splice.newLines, FileLayout.of(document).lineSeparator)
                val added = Validation.introduced(errors, diagnostics(file, without), Severity.ERROR, emptyList())
                return if (added.isEmpty()) removal.removed.toSet() else emptySet()
            }
            val together = if (suspects.size > 1) removable(suspects) else emptySet()
            together.ifEmpty { suspects.flatMapTo(mutableSetOf()) { removable(setOf(it)) } }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
    return unused.orEmpty()
}
