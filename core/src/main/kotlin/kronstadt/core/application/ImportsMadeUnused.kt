package kronstadt.core.application

import kronstadt.core.domain.FileHeader
import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.Severity
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.Validation
import kronstadt.core.domain.importsOrganizedIn
import kronstadt.core.domain.removingImports
import kronstadt.core.port.AnalysisSession
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.TimeMark
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Removes from each file this Shadow Workspace edited the imports the change made unused (ADR 0004): those the code
 * intelligence finds unused in the file's current text but not in its base, among those [eligible] keeps of the
 * suspects for that file. Its organize-imports decides, except for an import a star import new in its answer covers
 * (#81): that one is unused if removing it adds no error. Without an answer an import counts as used. The diagnostics
 * this takes share one [budget]. Shared by `kt_edit` and `kt_rename_symbol`; returns the imports removed, by file.
 */
internal suspend fun ShadowWorkspace.removeImportsMadeUnused(
    session: AnalysisSession,
    budget: Duration,
    eligible: (file: Path, suspects: Set<ImportDirective>) -> Set<ImportDirective>,
): Map<Path, List<String>> {
    val deadline = TimeSource.Monotonic.markNow() + budget
    return editedBases().mapNotNull { (file, base) ->
        val current = checkNotNull(current(file)) { "$file was edited, so it is in the Shadow Workspace" }
        val before = FileHeader.of(base.text).imports.map { it.directive }.toSet()
        if (before.isEmpty()) return@mapNotNull null
        val now = current.importsOrganizedIn(session.organizeImports(file, current) ?: return@mapNotNull null)
        // Only an import the base had can have been used before; most changes stop here, with no second round-trip.
        val suspects = eligible(file, (now.dropped + now.undecided).intersect(before))
        if (suspects.isEmpty()) return@mapNotNull null
        val candidates = suspects.intersect(now.dropped + session.unusedAmong(file, current, now.undecided.intersect(suspects), deadline))
        if (candidates.isEmpty()) return@mapNotNull null
        val then = base.importsOrganizedIn(session.organizeImports(file, base) ?: return@mapNotNull null)
        val unusedBefore = then.dropped + session.unusedAmong(file, base, then.undecided.intersect(candidates), deadline)
        val removal = current.removingImports(candidates - unusedBefore) ?: return@mapNotNull null
        replaceLines(file, removal.splice.lines, removal.splice.newLines)
        file to removal.removed.map { it.text }
    }.toMap()
}

/**
 * Those of [suspects], imports of [document] (the content of [file]), that are unused: removing them from it adds no
 * error. All of them are tried together first, then each alone. Without an answer by [deadline], or with a failed one,
 * an import counts as used.
 */
private suspend fun AnalysisSession.unusedAmong(
    file: Path,
    document: TextDocument,
    suspects: Set<ImportDirective>,
    deadline: TimeMark,
): Set<ImportDirective> {
    if (suspects.isEmpty()) return emptySet()
    val remaining = -deadline.elapsedNow()
    if (!remaining.isPositive()) return emptySet()
    val unused = try {
        withTimeoutOrNull(remaining) {
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
