package kronstadt.core.application

import kronstadt.core.domain.TextDocument

/**
 * Start offsets of [snippet], exactly as written, within [range] (offsets) of [document]'s text; overlapping
 * occurrences included ("aa" occurs twice in "aaa"). An occurrence never starts or ends inside a `\r\n` line break.
 */
internal fun exactOccurrences(document: TextDocument, snippet: String, range: IntRange): List<Int> {
    if (snippet.isEmpty()) return emptyList()
    val text = document.text
    fun splitsLineBreak(offset: Int) = offset in 1 until text.length && text[offset - 1] == '\r' && text[offset] == '\n'
    return generateSequence(text.indexOf(snippet, range.first).takeIf { it >= 0 }) { from ->
        text.indexOf(snippet, from + 1).takeIf { it >= 0 }
    }
        .takeWhile { it + snippet.length <= range.last + 1 }
        .filterNot { splitsLineBreak(it) || splitsLineBreak(it + snippet.length) }
        .toList()
}

/** The lines (1-based, trimmed) the occurrences starting at [offsets] are on, each once. */
internal fun occurrenceLines(document: TextDocument, offsets: List<Int>): List<Occurrence> =
    offsets.map { document.positionOf(it).line }.distinct().map { Occurrence(it + 1, document.line(it).trim()) }
