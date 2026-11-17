package kronstadt.core.domain

/**
 * Immutable document text with LSP position <-> string offset conversion.
 *
 * This is the "boss fight" from the design notes: every symbol-level edit goes through
 * [offsetOf] / [replace], so line-ending handling lives here and nowhere else.
 * Line terminators recognised: `\n`, `\r\n`, and a lone `\r` (as the LSP spec requires).
 */
public class TextDocument(public val text: String) {
    /** Offset of the first character of each line. Always contains at least one entry (line 0). */
    private val lineStarts: IntArray = computeLineStarts(text)

    public val lineCount: Int get() = lineStarts.size

    /**
     * Converts a position to a string offset.
     * Per the LSP spec, a [Position.character] past the end of the line falls back to the line end.
     */
    public fun offsetOf(position: Position): Int {
        require(position.line < lineCount) { "line ${position.line} out of bounds (document has $lineCount lines)" }
        val start = lineStarts[position.line]
        return start + minOf(position.character, lineContentEnd(position.line) - start)
    }

    public fun positionOf(offset: Int): Position {
        require(offset in 0..text.length) { "offset $offset out of bounds (length ${text.length})" }
        // Index of the last line start <= offset.
        val search = lineStarts.binarySearch(offset)
        val line = if (search >= 0) search else -search - 2
        return Position(line, offset - lineStarts[line])
    }

    /** The text of [line] without its terminator. */
    public fun line(line: Int): String {
        require(line in 0 until lineCount) { "line $line out of bounds (document has $lineCount lines)" }
        return text.substring(lineStarts[line], lineContentEnd(line))
    }

    /** Lines [first] to [last] (inclusive) as they are in the text, terminators between them included. */
    public fun lines(first: Int, last: Int): String {
        require(first in 0..last && last < lineCount) { "lines $first-$last out of bounds (document has $lineCount lines)" }
        return text.substring(lineStarts[first], lineContentEnd(last))
    }

    public fun slice(range: TextRange): String = text.substring(offsetOf(range.start), offsetOf(range.end))

    public fun replace(range: TextRange, newText: String): TextDocument {
        val start = offsetOf(range.start)
        val end = offsetOf(range.end)
        return TextDocument(text.substring(0, start) + newText + text.substring(end))
    }

    /**
     * Replaces whole [lines] (0-based, inclusive) with [newLines], each ended by [separator]. An empty range
     * (`n until n`) inserts before line `n`, or after the last line when `n` is [lineCount]. The other lines keep their
     * terminators. The last line has none, so whatever line ends the result has none either.
     */
    public fun replaceLines(lines: IntRange, newLines: List<String>, separator: String): TextDocument {
        val first = lines.first
        val end = lines.last + 1
        require(first in 0..end && end <= lineCount) { "lines $lines out of bounds (document has $lineCount lines)" }
        if (end < lineCount) {
            val inserted = newLines.joinToString("") { it + separator }
            return TextDocument(text.substring(0, lineStarts[first]) + inserted + text.substring(lineStarts[end]))
        }
        return when {
            newLines.isNotEmpty() && first == lineCount -> TextDocument(text + separator + newLines.joinToString(separator))
            newLines.isNotEmpty() -> TextDocument(text.substring(0, lineStarts[first]) + newLines.joinToString(separator))
            first == lineCount -> this
            first == 0 -> TextDocument("")
            else -> TextDocument(text.substring(0, lineContentEnd(first - 1)))
        }
    }

    /** Offset just past the last content character of [line], i.e. where its terminator starts. */
    private fun lineContentEnd(line: Int): Int {
        if (line == lineCount - 1) return text.length
        val nextStart = lineStarts[line + 1]
        val crlf = nextStart >= 2 && text[nextStart - 2] == '\r' && text[nextStart - 1] == '\n'
        return nextStart - if (crlf) 2 else 1
    }

    override fun equals(other: Any?): Boolean = other is TextDocument && other.text == text

    override fun hashCode(): Int = text.hashCode()

    override fun toString(): String = "TextDocument(${text.length} chars, $lineCount lines)"

    private companion object {
        fun computeLineStarts(text: String): IntArray {
            val starts = ArrayList<Int>().apply { add(0) }
            var i = 0
            while (i < text.length) {
                when (text[i]) {
                    '\n' -> starts.add(i + 1)
                    '\r' -> if (i + 1 < text.length && text[i + 1] == '\n') {
                        starts.add(i + 2)
                        i++
                    } else {
                        starts.add(i + 1)
                    }
                }
                i++
            }
            return starts.toIntArray()
        }
    }
}
