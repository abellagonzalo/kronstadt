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
 *
 * A file's imports depend on the other files' text, so the change is judged as a whole (#138): every file's current
 * round runs once every edited file is at its current text, and every base round once every edited file is at its
 * base. A round leaves its own file analysed with imports removed, which no other file can tell: imports are a file's
 * own. Analysing a file with a text is what brings it to that text; a round's own file needs no such step.
 */
internal suspend fun ShadowWorkspace.removeImportsMadeUnused(
    session: AnalysisSession,
    budget: Duration,
    eligible: (file: Path, suspects: Set<ImportDirective>) -> Set<ImportDirective>,
): Map<Path, List<String>> {
    val deadline = TimeSource.Monotonic.markNow() + budget
    val edited = editedBases().map { (file, base) ->
        Judged(file, base, checkNotNull(current(file)) { "$file was edited, so it is in the Shadow Workspace" })
    }
    // Only an import the base had can have been used before.
    val withImports = edited.filter { it.before.isNotEmpty() }
    if (withImports.isEmpty()) return emptyMap()
    edited.forEach { if (it !== withImports.first()) session.documentSymbols(it.file, it.current) }
    val candidates = withImports.mapNotNull { judged ->
        val now = judged.current.importsOrganizedIn(session.organizeImports(judged.file, judged.current) ?: return@mapNotNull null)
        // Most changes stop here, with no second round-trip.
        val suspects = eligible(judged.file, (now.dropped + now.undecided).intersect(judged.before))
        if (suspects.isEmpty()) return@mapNotNull null
        val unused = now.dropped + session.unusedAmong(judged.file, judged.current, now.undecided.intersect(suspects), deadline)
        suspects.intersect(unused).takeIf { it.isNotEmpty() }?.let { judged to it }
    }
    if (candidates.isEmpty()) return emptyMap()
    edited.forEach { if (it !== candidates.first().first) session.documentSymbols(it.file, it.base) }
    return candidates.mapNotNull { (judged, suspects) ->
        val then = judged.base.importsOrganizedIn(session.organizeImports(judged.file, judged.base) ?: return@mapNotNull null)
        val unusedBefore = then.dropped + session.unusedAmong(judged.file, judged.base, then.undecided.intersect(suspects), deadline)
        val removal = judged.current.removingImports(suspects - unusedBefore) ?: return@mapNotNull null
        replaceLines(judged.file, removal.splice.lines, removal.splice.newLines)
        judged.file to removal.removed.map { it.text }
    }.toMap()
}

/** An edited [file], its [base] and [current] text, and the imports its base had, [before]. */
private class Judged(val file: Path, val base: TextDocument, val current: TextDocument) {
    val before: Set<ImportDirective> = FileHeader.of(base.text).imports.map { it.directive }.toSet()
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
