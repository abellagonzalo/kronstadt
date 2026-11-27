package kronstadt.lsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.ImportUsage
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
 * What [organized], kotlin-lsp's organize-imports answer for [document] (the content of [file]), shows of [among], its
 * imports (ADR 0004): those it dropped are unused, and those it left undecided are used if the code refers to their
 * name, else unused if removing them adds no error, used if it adds one, and still undecided without an answer (see
 * [removableAmong]). Every other one is used. Without an organize-imports answer, every one is undecided. [budget]
 * bounds only the removal check.
 *
 * A collapsed package leaves every explicit import of it undecided, most of them still used; checking each by removing
 * it cost a file 2 diagnostics round-trips plus one per import, enough to use up the budget of a batch of files (#209).
 * Referring to the name settles most of them at no cost; a name that is referred to while its import is unused (a local
 * declaration shadowing it) only keeps that import.
 */
internal suspend fun AnalysisSession.unusedPerOrganizeImports(
    file: Path,
    document: TextDocument,
    organized: OrganizedImports?,
    among: Set<ImportDirective>,
    budget: Duration,
): ImportUsage {
    if (organized == null) return ImportUsage.undecided(among)
    val referred = document.namesInCode()
    val suspects = organized.undecided.intersect(among).filterTo(linkedSetOf()) { it.importedName !in referred }
    val removable = removableAmong(file, document, suspects, budget)
    return removable.copy(unused = organized.dropped.intersect(among) + removable.unused)
}

/**
 * Which of [suspects], imports of [document] (the content of [file]), removing from it adds no error (unused) or adds
 * one (used). All of them are tried together first, then each alone. Those not settled within [budget], or by a failed
 * answer, are undecided.
 */
private suspend fun AnalysisSession.removableAmong(
    file: Path,
    document: TextDocument,
    suspects: Set<ImportDirective>,
    budget: Duration,
): ImportUsage {
    if (suspects.isEmpty() || !budget.isPositive()) return ImportUsage.undecided(suspects)
    val unused = mutableSetOf<ImportDirective>()
    val used = mutableSetOf<ImportDirective>()
    try {
        withTimeoutOrNull(budget) {
            val errors = diagnostics(file, document)
            /** Whether removing [drop] adds no error; null if there is nothing to remove. */
            suspend fun removable(drop: Set<ImportDirective>): Boolean? {
                val removal = document.removingImports(drop) ?: return null
                val without = document.replaceLines(removal.splice.lines, removal.splice.newLines, FileLayout.of(document).lineSeparator)
                return Validation.introduced(errors, diagnostics(file, without), Severity.ERROR, emptyList()).isEmpty()
            }
            if (suspects.size > 1 && removable(suspects) == true) {
                unused += suspects
            } else {
                for (suspect in suspects) {
                    when (removable(setOf(suspect))) {
                        true -> unused += suspect
                        false -> used += suspect
                        null -> {}
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // What was settled before the failure stands; the rest stays undecided.
    }
    return ImportUsage(unused, suspects - unused - used)
}

/** The simple name this directive brings into scope, backticks aside: its alias if it has one, e.g. `C` for `a.b.C`. */
private val ImportDirective.importedName: String
    get() = (text.substringAfter(" as ", "").ifEmpty { text.substringAfterLast('.') }).trim().removeSurrounding("`")

/**
 * The names this text's code refers to, backticks aside: identifiers outside its package and import lines, comments,
 * and string and character literals (templates in strings too), and not right after a `.`, where a name is a member's.
 * Missing a name only costs a removal check.
 */
private fun TextDocument.namesInCode(): Set<String> {
    val names = mutableSetOf<String>()
    var i = 0
    var lineStart = true
    var afterDot = false
    /** Moves [i] past the first [end] at or after [from], or to the end of the text. */
    fun skipPast(end: String, from: Int) {
        val at = text.indexOf(end, from)
        i = if (at < 0) text.length else at + end.length
    }
    /** Moves [i] to the end of its line, its line break or the end of the text. */
    fun skipLine() {
        i = text.indexOf('\n', i).takeIf { it >= 0 } ?: text.length
    }
    while (i < text.length) {
        val c = text[i]
        when {
            c == '\n' -> { lineStart = true; afterDot = false; i++ }
            c.isWhitespace() -> i++
            text.startsWith("//", i) -> skipLine()
            text.startsWith("/*", i) -> skipPast("*/", i + 2)
            text.startsWith("\"\"\"", i) -> skipPast("\"\"\"", i + 3)
            c == '"' || c == '\'' -> {
                i++
                while (i < text.length && text[i] != c && text[i] != '\n') i += if (text[i] == '\\') 2 else 1
                i++
                lineStart = false; afterDot = false
            }
            c == '`' -> {
                val end = text.indexOf('`', i + 1).takeIf { it >= 0 } ?: text.length
                if (!afterDot) names += text.substring(i + 1, end)
                i = end + 1
                lineStart = false; afterDot = false
            }
            c.isJavaIdentifierStart() -> {
                val start = i
                while (i < text.length && text[i].isJavaIdentifierPart()) i++
                val word = text.substring(start, i)
                if (lineStart && (word == "import" || word == "package")) skipLine()
                else if (!afterDot) names += word
                lineStart = false; afterDot = false
            }
            else -> { afterDot = c == '.'; lineStart = false; i++ }
        }
    }
    return names
}
