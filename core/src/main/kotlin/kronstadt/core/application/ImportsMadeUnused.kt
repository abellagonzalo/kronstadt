package kronstadt.core.application

import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.imports
import kronstadt.core.domain.removingImports
import kronstadt.core.port.AnalysisSession
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Removes from each file this Shadow Workspace edited the imports the change made unused (ADR 0004): those the code
 * intelligence finds to be Unused Imports in the file's current text and used in its base, among those [eligible] keeps
 * of the file's base imports. Being unsure keeps an import either way, and lists it as unchecked: undecided in the
 * current text counts as used, unless the base shows it was already unused, and undecided in the base as maybe already
 * unused, e.g. in a base that didn't compile (#193). Each of a file's questions has the whole [budget], so a file whose
 * checks are slow can't leave the others without an answer (#209). Shared by Edit and RenameSymbol.
 *
 * A file's imports can depend on another edited file, so the change is judged as a whole (#138): every edited file is
 * brought to its current text before any is asked about its current text, and to its base before any is asked about
 * its base. Analysing a file with a text is what brings it to that text, and asking about a file brings it there too.
 * Once any file had suspects, every edited file is left at its base text in the session.
 */
internal suspend fun ShadowWorkspace.removeImportsMadeUnused(
    session: AnalysisSession,
    budget: Duration,
    eligible: (file: Path, imports: Set<ImportDirective>) -> Set<ImportDirective>,
): ImportsMadeUnused {
    val edited = editedBases().map { (file, base) ->
        EditedTexts(file, base, checkNotNull(current(file)) { "$file was edited, so it is in the Shadow Workspace" })
    }
    /** Brings every edited file but [asked], which asking about brings there, to the text [of] gives it. */
    suspend fun bringAll(asked: EditedTexts, of: (EditedTexts) -> TextDocument) =
        edited.forEach { if (it !== asked) session.documentSymbols(it.file, of(it)) }

    // Only an import the base had can have been used before.
    val asked = edited.mapNotNull { texts -> eligible(texts.file, texts.baseImports).takeIf { it.isNotEmpty() }?.let { texts to it } }
    if (asked.isEmpty()) return ImportsMadeUnused()
    bringAll(asked.first().first) { it.current }
    val suspected = asked.mapNotNull { (texts, among) ->
        val now = session.unusedImports(texts.file, texts.current, among, budget)
        Suspected(texts, now.unused.intersect(among), now.undecided.intersect(among)).takeIf { it.suspects.isNotEmpty() || it.undecided.isNotEmpty() }
    }
    // Most changes stop here, with no second round-trip.
    if (suspected.isEmpty()) return ImportsMadeUnused()
    bringAll(suspected.first().texts) { it.base }
    val removed = mutableMapOf<Path, List<String>>()
    val unchecked = mutableMapOf<Path, List<String>>()
    for ((texts, suspects, undecided) in suspected) {
        val before = session.unusedImports(texts.file, texts.base, suspects + undecided, budget)
        val kept = suspects.intersect(before.undecided) + (undecided - before.unused)
        if (kept.isNotEmpty()) unchecked[texts.file] = texts.baseImports.filter { it in kept }.map { it.text }
        val removal = texts.current.removingImports(before.usedAmong(suspects)) ?: continue
        replaceLines(texts.file, removal.splice.lines, removal.splice.newLines)
        removed[texts.file] = removal.removed.map { it.text }
    }
    return ImportsMadeUnused(removed, unchecked)
}

/**
 * What [removeImportsMadeUnused] did, by file: the imports it [removed], and those it kept [unchecked], for want of an
 * answer about them in time, each in the file's order.
 */
internal data class ImportsMadeUnused(
    val removed: Map<Path, List<String>> = emptyMap(),
    val unchecked: Map<Path, List<String>> = emptyMap(),
)

/** An edited [file]'s [base] and [current] text, and the imports its base had, [baseImports]. */
private class EditedTexts(val file: Path, val base: TextDocument, val current: TextDocument) {
    val baseImports: Set<ImportDirective> = base.imports.toSet()
}

/**
 * An edited file's [texts], the imports in it the change may have made unused, [suspects]: unused at its current text,
 * and those it may have but there is no answer about, [undecided].
 */
private data class Suspected(val texts: EditedTexts, val suspects: Set<ImportDirective>, val undecided: Set<ImportDirective>)
