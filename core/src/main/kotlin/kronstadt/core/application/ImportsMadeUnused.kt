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
 * this takes share one [budget]. Shared by Edit and RenameSymbol; returns the imports removed, by file.
 *
 * A file's imports can depend on another edited file, so the change is judged as a whole (#138): every edited file is
 * brought to its current text before any is asked about its current text, and to its base before any is asked about
 * its base. Analysing a file with a text is what brings it to that text, and asking about a file brings it there too.
 * Checking one file with some of its imports removed changes nothing another file sees, since imports are a file's
 * own. Once any file had suspects, every edited file is left at its base text in the session.
 */
internal suspend fun ShadowWorkspace.removeImportsMadeUnused(
    session: AnalysisSession,
    budget: Duration,
    eligible: (file: Path, suspects: Set<ImportDirective>) -> Set<ImportDirective>,
): Map<Path, List<String>> {
    val deadline = TimeSource.Monotonic.markNow() + budget
    val edited = editedBases().map { (file, base) ->
        EditedTexts(file, base, checkNotNull(current(file)) { "$file was edited, so it is in the Shadow Workspace" })
    }
    /** Brings every edited file but [asked], which asking about brings there, to the text [of] gives it. */
    suspend fun bringAll(asked: EditedTexts, of: (EditedTexts) -> TextDocument) =
        edited.forEach { if (it !== asked) session.documentSymbols(it.file, of(it)) }

    // Only an import the base had can have been used before.
    val withImports = edited.filter { it.baseImports.isNotEmpty() }
    if (withImports.isEmpty()) return emptyMap()
    bringAll(withImports.first()) { it.current }
    val suspected = withImports.mapNotNull { texts ->
        val now = texts.current.importsOrganizedIn(session.organizeImports(texts.file, texts.current) ?: return@mapNotNull null)
        // Most changes stop here, with no second round-trip.
        val suspects = eligible(texts.file, (now.dropped + now.undecided).intersect(texts.baseImports))
        if (suspects.isEmpty()) return@mapNotNull null
        val unused = now.dropped + session.unusedAmong(texts.file, texts.current, now.undecided.intersect(suspects), deadline)
        suspects.intersect(unused).takeIf { it.isNotEmpty() }?.let { Suspected(texts, it) }
    }
    if (suspected.isEmpty()) return emptyMap()
    bringAll(suspected.first().texts) { it.base }
    return suspected.mapNotNull { (texts, suspects) ->
        val then = texts.base.importsOrganizedIn(session.organizeImports(texts.file, texts.base) ?: return@mapNotNull null)
        val unusedBefore = then.dropped + session.unusedAmong(texts.file, texts.base, then.undecided.intersect(suspects), deadline)
        val removal = texts.current.removingImports(suspects - unusedBefore) ?: return@mapNotNull null
        replaceLines(texts.file, removal.splice.lines, removal.splice.newLines)
        texts.file to removal.removed.map { it.text }
    }.toMap()
}

/** An edited [file]'s [base] and [current] text, and the imports its base had, [baseImports]. */
private class EditedTexts(val file: Path, val base: TextDocument, val current: TextDocument) {
    val baseImports: Set<ImportDirective> = FileHeader.of(base.text).imports.map { it.directive }.toSet()
}

/** An edited file's [texts] and the imports in it the change may have made unused, [suspects]: unused at its current text. */
private data class Suspected(val texts: EditedTexts, val suspects: Set<ImportDirective>)

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
