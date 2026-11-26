package kronstadt.core.application

import kronstadt.core.domain.FileLayout
import kronstadt.core.domain.TextDocument

/**
 * A Snippet (GLOSSARY.md): text an agent copies from a file to point at a place in it, `edit_in`'s `old` or
 * `kt_get_definition`'s `at`. It must occur exactly once in its scope, exactly as written except that any line break
 * matches any line ending (ADR 0004). This is the only place that matches one.
 */
public object Snippet {
    /**
     * The one place [snippet] occurs within [range] (offsets) of [document], or a [Miss] saying how often and where it
     * occurs instead.
     */
    internal fun find(document: TextDocument, snippet: String, range: IntRange): Found {
        val matches = matches(document, snippet, range)
        matches.singleOrNull()?.let { return Match(it) }
        val hint = if (matches.isEmpty()) indentationHint(document, snippet, range) else null
        return Miss(snippet, matches.size, SourceLine.startingAt(document, matches), hint)
    }

    /** What [find] found: a [Match] or a [Miss]. */
    public sealed interface Found

    /** The Snippet occurs exactly once, at [range] (offsets), which may be longer or shorter than it (its line breaks). */
    public data class Match(val range: IntRange) : Found

    /**
     * [snippet] occurs [count] times, not exactly once; [lines] are the lines it starts on, each once. With no
     * occurrence, [indentation] says where it would occur once if indentation were ignored.
     */
    public data class Miss(
        val snippet: String,
        val count: Int,
        val lines: List<SourceLine>,
        val indentation: IndentationHint? = null,
    ) : Found

    /**
     * The ranges (offsets) [snippet] matches at within [range] of [document]'s text, overlapping matches included ("aa"
     * matches twice in "aaa"). Each line break in [snippet] (a [TextDocument.LINE_BREAK]) matches one whole line break of
     * the text, of any of those kinds, so a match may be longer or shorter than [snippet]; everything else matches
     * exactly as written, whitespace included (ADR 0004). A match never starts or ends inside a `\r\n`.
     */
    private fun matches(document: TextDocument, snippet: String, range: IntRange): List<IntRange> {
        if (snippet.isEmpty()) return emptyList()
        val text = document.text
        val lineBreak = TextDocument.LINE_BREAK
        val pattern = Regex(snippet.split(lineBreak).joinToString("(?:${lineBreak.pattern})") { Regex.escape(it) })
        return generateSequence(pattern.find(text, range.first)) { pattern.find(text, it.range.first + 1) }
            .map { it.range }
            .takeWhile { it.first <= range.last }
            .filter { it.last <= range.last }
            .toList()
    }

    /**
     * Where [snippet], which doesn't occur exactly as written within [range] (offsets) of [document], occurs once if each
     * line's leading whitespace is ignored, or null if it occurs there zero or several times or its lines' indentation
     * doesn't differ. A first line without leading whitespace may end a line of the file, as an exact match may; one with
     * leading whitespace must cover a whole line's text after its indentation.
     */
    private fun indentationHint(document: TextDocument, snippet: String, range: IntRange): IndentationHint? {
        val lines = snippet.lines()
        val first = lines.first()
        // A one-line snippet without leading whitespace reads the same with indentation ignored.
        if (lines.size == 1 && FileLayout.indentOf(first).isEmpty()) return null
        val scope = document.positionOf(range.first).line..document.positionOf(range.last + 1).line

        /** How the file and [snippet] indent each line both indent, if [snippet] matches from [start] with indentation ignored, else null. */
        fun indentsFrom(start: Int): List<IndentationHint.Indents>? {
            val indents = mutableListOf<IndentationHint.Indents>()
            for ((j, line) in lines.withIndex()) {
                val fileLine = document.line(start + j)
                val indent = FileLayout.indentOf(line)
                val text = line.substring(indent.length)
                val fileText = fileLine.substring(FileLayout.indentOf(fileLine).length)
                val lineMatches = when {
                    j == 0 && indent.isEmpty() -> lines.size > 1 && fileLine.endsWith(line)
                    j == lines.lastIndex -> fileText.startsWith(text)
                    else -> fileText == text
                }
                if (!lineMatches) return null
                if (text.isNotEmpty() && (j > 0 || indent.isNotEmpty())) indents += IndentationHint.Indents(start + j + 1, FileLayout.indentOf(fileLine), indent)
            }
            return indents
        }

        val matches = (scope.first..scope.last - lines.lastIndex).mapNotNull { start -> indentsFrom(start)?.let { start to it } }
        val (start, indents) = matches.singleOrNull() ?: return null
        if (indents.all { it.file == it.snippet }) return null
        // Each line differs the same way when, past the indentation the two share at its end, every line has the same difference.
        val differences = indents.map { (_, file, snippet) ->
            val sharedEnd = file.reversed().commonPrefixWith(snippet.reversed()).length
            file.dropLast(sharedEnd) to snippet.dropLast(sharedEnd)
        }
        return IndentationHint(start + 1, indents.first().takeIf { differences.distinct().size == 1 })
    }
}

/** A 1-based [line] of a file and its trimmed [source]: where a Snippet, or a name, occurs. */
public data class SourceLine(val line: Int, val source: String) {
    internal companion object {
        /** The lines [ranges] (offsets) of [document] start on, each once, in order. */
        fun startingAt(document: TextDocument, ranges: List<IntRange>): List<SourceLine> =
            ranges.map { document.positionOf(it.first).line }.distinct().map { SourceLine(it + 1, document.line(it).trim()) }
    }
}

/**
 * A snippet that doesn't occur exactly as written occurs once if each line's leading whitespace is ignored, starting on
 * 1-based [line]. [indents] are how the file and the snippet indent a line, when every line differs the same way; null
 * when the lines differ in different ways. Only a hint: nothing ever matches this way (ADR 0004).
 */
public data class IndentationHint(val line: Int, val indents: Indents?) {
    /**
     * The leading whitespace the [file] and the [snippet] put on the first line they both indent, 1-based [line]. That
     * is the hint's start line, or a later one when the snippet's first line starts mid-line without indentation.
     */
    public data class Indents(val line: Int, val file: String, val snippet: String)
}
