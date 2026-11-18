package kronstadt.core.domain

/**
 * One import directive without the `import` keyword, e.g. `a.b.C`, `a.b.*` or `a.b.C as D`. Two directives are the
 * same when they import the same name under the same alias, backticks or whitespace aside; they sort by that name.
 */
internal class ImportDirective(
    /** The directive as it is written in a file, whitespace normalized. */
    val text: String,
    private val key: String,
) : Comparable<ImportDirective> {
    override fun compareTo(other: ImportDirective): Int = key.compareTo(other.key)

    override fun equals(other: Any?): Boolean = other is ImportDirective && other.key == key

    override fun hashCode(): Int = key.hashCode()

    /** The directive as a line of a file. */
    val line: String get() = "import $text"

    override fun toString(): String = text

    companion object {
        /** [raw] as a directive, with or without the `import` keyword and a trailing `;`. */
        fun parse(raw: String): Result<ImportDirective> = FileHeader.directive(raw)?.let { Result.success(it) }
            ?: Result.failure(IllegalArgumentException("not an import directive: '$raw'"))
    }
}

/** Whole [lines] (0-based; empty to insert before `lines.first`) to replace with [newLines]. */
internal data class LineSplice(val lines: IntRange, val newLines: List<String>)

/**
 * The splice that adds those of [imports] this file doesn't have yet, or null if it has them all. Nothing already in
 * the file moves or goes: each missing import goes on its own line right after the import that sorts just before it,
 * or before the first in order if none does, so a block laid out in sorted groups stays that way. Without an import
 * list, a new one, sorted, goes after the package line (else after the file annotations, else at the top), one blank
 * line from what comes before and after it.
 */
internal fun TextDocument.addingImports(imports: List<ImportDirective>): LineSplice? {
    val header = FileHeader.of(text)
    val existing = header.imports.map { it.directive }.toSet()
    val missing = imports.distinct().filterNot { it in existing }.sorted()
    if (missing.isEmpty()) return null
    if (header.imports.isNotEmpty()) return insertedInto(header.imports, missing)
    val directives = missing.map { it.line }
    val at = header.preambleEnd?.let { positionOf(it).line + 1 } ?: 0
    val blankBefore = if (at > 0) listOf("") else emptyList()
    val blankAfter = if (at < lineCount - 1 && line(at).isNotBlank()) listOf("") else emptyList()
    return LineSplice(at until at, blankBefore + directives + blankAfter)
}

/** One line of an import list, and the directive it anchors new ones by, if any. */
private class ListLine(val text: String, val anchor: ImportDirective?)

/**
 * The lines of the import list from its first directive to its last, with [missing] each placed by its neighbours.
 * Only a line that a directive starts and holds alone is a neighbour, so a new import never goes on or above a line it
 * shares with the package line or another import; with no such line, new ones go after the list.
 */
private fun TextDocument.insertedInto(scanned: List<FileHeader.ScannedImport>, missing: List<ImportDirective>): LineSplice {
    val first = positionOf(scanned.first().start).line
    val last = positionOf(scanned.last().end).line
    val byLine = scanned.groupBy { positionOf(it.start).line }
    val lines = (first..last).mapTo(mutableListOf()) { n ->
        val alone = byLine[n]?.singleOrNull()?.takeIf { line(n).take(positionOf(it.start).character).isBlank() }
        ListLine(line(n), alone?.directive)
    }
    missing.forEach { directive ->
        val anchors = lines.withIndex().mapNotNull { (index, line) -> line.anchor?.let { index to it } }
        val at = anchors.filter { it.second < directive }.maxByOrNull { it.second }?.let { it.first + 1 }
            ?: anchors.minByOrNull { it.second }?.first
            ?: lines.size
        lines.add(at, ListLine(directive.line, directive))
    }
    return LineSplice(first..last, lines.map { it.text })
}
