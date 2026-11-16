package kronstadt.core.domain

/**
 * A declaration's contract without its body: from its first line (KDoc, annotations, modifiers) to just before the body
 * or initializer. Found by text after the name, so it works on any Kotlin source, a library's included.
 */
public object DeclarationHead {
    private val ACCESSOR = Regex("""^((private|protected|internal|public)\s+)?(get|set)\b""")

    /**
     * The head of [symbol] in [document], starting at 0-based line [firstLine]. It ends at the first `{` or `=` after
     * the name that isn't nested in brackets, at a delegating `by`, or before a property's accessor on a new line;
     * otherwise it is the whole declaration. Trailing whitespace is dropped.
     */
    public fun of(document: TextDocument, symbol: CodeSymbol, firstLine: Int): String {
        val text = document.text
        val start = document.offsetOf(Position(firstLine, 0))
        val end = document.offsetOf(symbol.range.end)
        var depth = 0
        var i = document.offsetOf(symbol.selectionRange.end).coerceAtLeast(start)
        while (i < end) {
            val c = text[i]
            when {
                c == '"' || c == '\'' -> i = skipQuoted(text, i, end) - 1
                c == '/' && text.startsWith("//", i) -> i = text.indexOf('\n', i).let { if (it < 0) end else it } - 1
                c == '/' && text.startsWith("/*", i) -> i = text.indexOf("*/", i + 2).let { if (it < 0) end else it + 1 }
                c == '-' && text.startsWith("->", i) -> i++
                c == '(' || c == '[' || c == '<' -> depth++
                c == ')' || c == ']' || c == '>' -> depth = maxOf(0, depth - 1)
                depth > 0 -> {}
                c == '{' -> break
                c == '=' && text.getOrNull(i + 1) != '=' && text.getOrNull(i - 1) !in setOf('=', '!', '<', '>') -> break
                c == 'b' && text.startsWith("by", i) && text.getOrNull(i - 1)?.isWhitespace() == true &&
                    text.getOrNull(i + 2)?.isWhitespace() == true -> break
                c == '\n' && ACCESSOR.containsMatchIn(text.substring(i + 1, minOf(end, lineEnd(text, i + 1))).trimStart()) -> break
            }
            i++
        }
        return text.substring(start, minOf(i, end)).trimEnd()
    }

    private fun lineEnd(text: String, from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it }

    /** Index just past the string or char literal opening at [open]; raw strings (`"""`) included. */
    private fun skipQuoted(text: String, open: Int, end: Int): Int {
        if (text.startsWith("\"\"\"", open)) return text.indexOf("\"\"\"", open + 3).let { if (it < 0) end else it + 3 }
        val quote = text[open]
        var i = open + 1
        while (i < end && text[i] != quote) i += if (text[i] == '\\') 2 else 1
        return minOf(i + 1, end)
    }
}
