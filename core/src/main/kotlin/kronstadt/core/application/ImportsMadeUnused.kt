package kronstadt.core.application

import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.imports
import kronstadt.core.domain.removingImports
import kronstadt.core.port.AnalysisSession
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Removes from each file this Shadow Workspace edited the imports the change made unused (ADR 0004): those the code
 * intelligence finds to be Unused Imports in the file's current text but not in its base, among those [eligible] keeps
 * of the file's base imports. Without an answer an import counts as used. The questions share one [budget]. Shared by
 * Edit and RenameSymbol; returns the imports removed, by file.
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
): Map<Path, List<String>> {
    val deadline = TimeSource.Monotonic.markNow() + budget
    fun remaining() = (-deadline.elapsedNow()).coerceAtLeast(Duration.ZERO)
    val edited = editedBases().map { (file, base) ->
        EditedTexts(file, base, checkNotNull(current(file)) { "$file was edited, so it is in the Shadow Workspace" })
    }
    /** Brings every edited file but [asked], which asking about brings there, to the text [of] gives it. */
    suspend fun bringAll(asked: EditedTexts, of: (EditedTexts) -> TextDocument) =
        edited.forEach { if (it !== asked) session.documentSymbols(it.file, of(it)) }

    // Only an import the base had can have been used before.
    val asked = edited.mapNotNull { texts -> eligible(texts.file, texts.baseImports).takeIf { it.isNotEmpty() }?.let { texts to it } }
    if (asked.isEmpty()) return emptyMap()
    bringAll(asked.first().first) { it.current }
    val suspected = asked.mapNotNull { (texts, among) ->
        session.unusedImports(texts.file, texts.current, among, remaining()).takeIf { it.isNotEmpty() }?.let { Suspected(texts, it) }
    }
    // Most changes stop here, with no second round-trip.
    if (suspected.isEmpty()) return emptyMap()
    bringAll(suspected.first().texts) { it.base }
    return suspected.mapNotNull { (texts, suspects) ->
        val unusedBefore = session.unusedImports(texts.file, texts.base, suspects, remaining())
        val removal = texts.current.removingImports(suspects - unusedBefore) ?: return@mapNotNull null
        replaceLines(texts.file, removal.splice.lines, removal.splice.newLines)
        texts.file to removal.removed.map { it.text }
    }.toMap()
}

/** An edited [file]'s [base] and [current] text, and the imports its base had, [baseImports]. */
private class EditedTexts(val file: Path, val base: TextDocument, val current: TextDocument) {
    val baseImports: Set<ImportDirective> = base.imports.toSet()
}

/** An edited file's [texts] and the imports in it the change may have made unused, [suspects]: unused at its current text. */
private data class Suspected(val texts: EditedTexts, val suspects: Set<ImportDirective>)
