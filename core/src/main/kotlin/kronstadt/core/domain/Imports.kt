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

    /** Whether this is a star import, e.g. `a.b.*`. */
    val isStar: Boolean get() = key.endsWith(".*")

    /** Whether [star], a star import, brings this directive's name into scope too: it imports from that package, without an alias. */
    fun isCoveredBy(star: ImportDirective): Boolean =
        star.isStar && !isStar && !isAliased && key.substringBeforeLast('.') + ".*" == star.key

    /** Whether this directive imports its name under an alias, e.g. `a.b.C as D`. */
    val isAliased: Boolean get() = " as " in key

    /** How many leading dot-separated segments of its imported name this directive shares with [other]'s. */
    fun sharedSegments(other: ImportDirective): Int =
        segments.zip(other.segments).takeWhile { (a, b) -> a == b }.size

    /** The dot-separated segments of the imported name, alias aside, e.g. `a`, `b`, `*` for `a.b.*`. */
    val segments: List<String> get() = key.substringBefore(" as ").split('.')

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

private const val BYTE_ORDER_MARK = "﻿"

/**
 * The splice that adds those of [imports] this file doesn't have yet, or null if it has them all. Nothing already in
 * the file moves or goes: each missing import, in sorted order, goes on its own line next to its closest neighbours,
 * and those added before it count as neighbours too. Its neighbours are the imports sharing the most leading
 * dot-separated segments of the name with it (`kotlin` and `kotlinx` share none), aliased only with aliased ones; it
 * goes right after the one of them that sorts just before it, or before the first in order if none does. If no import
 * shares a segment with it, it goes into its group of IntelliJ's default layout (others, `java.*`, `javax.*`,
 * `kotlin.*`, then aliased ones): after the last import in an earlier group or sorting before it in its own, else
 * before the first. So a block in that layout, or in sorted groups by package, stays that way. Without an import
 * list, a new one, sorted, goes after the package line (else after the file annotations, else after the leading
 * comments a blank line ends, such as a licence, even one written as a KDoc, but above a KDoc or other comment
 * attached to the first declaration, else
 * after the shebang line, else at the top, right after a byte order mark), one blank line from what comes before and
 * after it. A leading byte order mark never moves.
 */
internal fun TextDocument.addingImports(imports: List<ImportDirective>): LineSplice? {
    val header = FileHeader.of(text)
    val existing = header.imports.map { it.directive }.toSet()
    val missing = imports.distinct().filterNot { it in existing }.sorted()
    if (missing.isEmpty()) return null
    if (header.imports.isNotEmpty()) return insertedInto(header.imports, missing)
    val directives = missing.map { it.line }
    val at = header.preambleEnd?.let { positionOf(it).line + 1 } ?: 0
    if (at == 0 && text.startsWith(BYTE_ORDER_MARK)) {
        // The mark shares line 0 with what follows it, so the block goes on that line, between the two.
        val rest = line(0).drop(1)
        val blankAfter = if (rest.isNotBlank()) listOf("") else emptyList()
        val block = listOf(BYTE_ORDER_MARK + directives.first()) + directives.drop(1)
        return LineSplice(0..0, block + blankAfter + rest)
    }
    val blankBefore = if (at > 0) listOf("") else emptyList()
    val blankAfter = if (at < lineCount - 1 && line(at).isNotBlank()) listOf("") else emptyList()
    return LineSplice(at until at, blankBefore + directives + blankAfter)
}

/** One line of an import list, and the directive it anchors new ones by, if any. */
private class ListLine(val text: String, val anchor: ImportDirective?)

/**
 * The lines of the import list from its first directive to its last, with [missing] each placed by its neighbours.
 * Only a line that a directive starts and holds alone is a neighbour, so a new import never goes on or above a line it
 * shares with the package line or another import; with no such line, new ones go after the list. A byte order mark
 * in front of the first line doesn't count, and stays the first line's first character.
 */
private fun TextDocument.insertedInto(scanned: List<FileHeader.ScannedImport>, missing: List<ImportDirective>): LineSplice {
    val first = positionOf(scanned.first().start).line
    val last = positionOf(scanned.last().end).line
    val mark = if (first == 0 && text.startsWith(BYTE_ORDER_MARK)) BYTE_ORDER_MARK else ""
    val byLine = scanned.groupBy { positionOf(it.start).line }
    val lines = (first..last).mapTo(mutableListOf()) { n ->
        val written = if (n == first) line(n).removePrefix(mark) else line(n)
        val dropped = line(n).length - written.length
        val alone = byLine[n]?.singleOrNull()?.takeIf { written.take(positionOf(it.start).character - dropped).isBlank() }
        ListLine(written, alone?.directive)
    }
    missing.forEach { directive ->
        val anchors = lines.withIndex().mapNotNull { (index, line) -> line.anchor?.let { index to it } }
        val at = slotFor(directive, anchors) ?: lines.size
        lines.add(at, ListLine(directive.line, directive))
    }
    return LineSplice(first..last, lines.mapIndexed { index, line -> if (index == 0) mark + line.text else line.text })
}

/**
 * The index [directive] goes at among [anchors], the neighbour lines of an import list by index, or null with none.
 * It goes by those sharing the most leading segments with it, if any share one; else by its group in IntelliJ's
 * default layout, after the last line in an earlier group or sorting before it in its own, else before the first.
 */
private fun slotFor(directive: ImportDirective, anchors: List<Pair<Int, ImportDirective>>): Int? {
    val kin = anchors.filter { it.second.isAliased == directive.isAliased }
    val longest = kin.maxOfOrNull { directive.sharedSegments(it.second) } ?: 0
    if (longest > 0) {
        val nearest = kin.filter { directive.sharedSegments(it.second) == longest }
        return nearest.filter { it.second < directive }.maxByOrNull { it.second }?.let { it.first + 1 }
            ?: nearest.minBy { it.second }.first
    }
    val group = directive.layoutGroup
    return anchors.lastOrNull { (_, it) -> it.layoutGroup < group || it.layoutGroup == group && it < directive }
        ?.let { it.first + 1 }
        ?: anchors.firstOrNull()?.first
}

/** The directive's group in IntelliJ's default Kotlin import layout: others, `java.*`, `javax.*`, `kotlin.*`, then aliased ones. */
private val ImportDirective.layoutGroup: Int
    get() = if (isAliased) 4 else listOf("java", "javax", "kotlin").indexOf(segments.first()) + 1

/** The imports [removingImports] removes, and the [splice] that removes them. */
internal class ImportRemoval(val removed: List<ImportDirective>, val splice: LineSplice)

/**
 * What [organized], a file's text after the code intelligence organized its imports, tells of the file's own imports:
 * those it [dropped], found unused, and those it left [undecided]: explicit imports it dropped only because a star
 * import of their package, new in [organized], now brings them in. kotlin-lsp collapses a package into a star once
 * enough of its names are used, whether or not one of them still is (#81). Every other import is kept: one still in
 * [organized], and a star import whose package it newly imports by name.
 */
internal class OrganizedImports(val dropped: Set<ImportDirective>, val undecided: Set<ImportDirective>)

/** What [organized], this file's text after the code intelligence organized its imports, tells of its imports. */
internal fun TextDocument.importsOrganizedIn(organized: TextDocument): OrganizedImports {
    val own = FileHeader.of(text).imports.map { it.directive }
    val left = FileHeader.of(organized.text).imports.map { it.directive }.toSet()
    val (newStars, newNames) = left.filter { it !in own }.partition { it.isStar }
    val gone = own.filter { directive -> directive !in left && newNames.none { it.isCoveredBy(directive) } }
    val (undecided, dropped) = gone.partition { directive -> newStars.any(directive::isCoveredBy) }
    return OrganizedImports(dropped.toSet(), undecided.toSet())
}

/**
 * The removal of those of [drop] this file has, each with its line; null if none goes. Only an import alone on its line
 * goes, so one sharing it with another import, the package line or a comment stays. Nothing else in the import list
 * changes, except that a blank line goes where a removed import would leave it next to another blank line or at the
 * list's start or end; an emptied list goes with the blank line that set it apart, so no stray one stays.
 */
internal fun TextDocument.removingImports(drop: Set<ImportDirective>): ImportRemoval? {
    val header = FileHeader.of(text)
    val removed = header.imports.filter { it.directive in drop && heldAlone(it) }
    if (removed.isEmpty()) return null
    val first = positionOf(header.imports.first().start).line
    val last = positionOf(header.imports.last().end).line
    val gone = removed.map { positionOf(it.start).line }.toSet()
    val kept = mutableListOf<String>()
    var removedSinceKept = false
    for (n in first..last) {
        when {
            n in gone -> removedSinceKept = true
            line(n).isBlank() && removedSinceKept && kept.lastOrNull()?.isBlank() != false -> {}
            else -> {
                kept += line(n)
                removedSinceKept = false
            }
        }
    }
    // The list ends with an import, so a blank line left at its end bordered a removed one.
    val lines = kept.dropLastWhile { it.isBlank() }
    val directives = removed.map { it.directive }
    if (lines.isNotEmpty()) return ImportRemoval(directives, LineSplice(first..last, lines))
    val apart = first == 0 || line(first - 1).isBlank()
    val emptied = when {
        // The last line of a file ending in a line break is the empty one after it, not a blank line of its own.
        apart && last + 1 < lineCount - 1 && line(last + 1).isBlank() -> first..(last + 1)
        first > 0 && line(first - 1).isBlank() -> (first - 1)..last
        else -> first..last
    }
    return ImportRemoval(directives, LineSplice(emptied, emptyList()))
}

/** Whether [scanned] starts its line and nothing but whitespace or a `;` follows it there. */
private fun TextDocument.heldAlone(scanned: FileHeader.ScannedImport): Boolean {
    val start = positionOf(scanned.start)
    val end = positionOf(scanned.end)
    return start.line == end.line && line(start.line).take(start.character).isBlank() &&
        line(end.line).drop(end.character).trim().let { it.isEmpty() || it == ";" }
}
