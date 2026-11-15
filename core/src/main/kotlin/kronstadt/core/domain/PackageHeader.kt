package kronstadt.core.domain

/**
 * The package this Kotlin file declares in its header, or `""` for the default package.
 *
 * Only the header counts, as in the Kotlin grammar: an optional byte order mark and shebang line, then whitespace, line and (nested) block
 * comments and `@file:` annotations, then `package`. If anything else comes first, or the file is empty, the file is in
 * the default package, whatever its body contains. Backticked segments are unquoted; whitespace and comments inside
 * the name and a trailing `;` are allowed.
 */
public val TextDocument.packageName: String
    get() = PackageHeader(text).read()

private class PackageHeader(private val text: String) {
    private var i = 0

    fun read(): String {
        if (peek() == '\uFEFF') i++
        if (text.startsWith("#!", i)) skipLine()
        skipWhitespace()
        while (peek() == '@') {
            if (!skipFileAnnotation()) return ""
            skipWhitespace()
        }
        if (!keyword("package")) return ""
        val segments = mutableListOf<String>()
        do {
            if (segments.isNotEmpty()) i++ // '.'
            skipWhitespace()
            segments += identifier()
            skipWhitespace()
        } while (peek() == '.')
        return segments.joinToString(".")
    }

    private fun peek(): Char? = text.getOrNull(i)

    /** Skips whitespace, line comments and (nested) block comments. */
    private fun skipWhitespace() {
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                text.startsWith("//", i) -> skipLine()
                text.startsWith("/*", i) -> skipBlockComment()
                else -> return
            }
        }
    }

    private fun skipLine() {
        while (i < text.length && text[i] != '\n' && text[i] != '\r') i++
    }

    /** Kotlin block comments nest; an unclosed one runs to the end of the file. */
    private fun skipBlockComment() {
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("/*", i) -> { depth++; i += 2 }
                text.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return }
                else -> i++
            }
        }
    }

    /**
     * Skips `@file:Name(args)` or `@file:[A B(args)]`. False if the annotation at [i] isn't a file annotation, i.e.
     * it belongs to a declaration and the header has ended.
     */
    private fun skipFileAnnotation(): Boolean {
        i++ // '@'
        skipWhitespace()
        if (!keyword("file")) return false
        skipWhitespace()
        if (peek() != ':') return false
        i++
        skipWhitespace()
        if (peek() == '[') {
            skipBalanced('[', ']')
            return true
        }
        identifier()
        skipWhitespace()
        while (peek() == '.') {
            i++
            skipWhitespace()
            identifier()
            skipWhitespace()
        }
        if (peek() == '<') skipBalanced('<', '>')
        skipWhitespace()
        if (peek() == '(') skipBalanced('(', ')')
        return true
    }

    /** The identifier at [i], without its backticks. */
    private fun identifier(): String {
        if (peek() == '`') {
            val close = text.indexOf('`', i + 1).let { if (it < 0) text.length else it }
            return text.substring(i + 1, close).also { i = minOf(close + 1, text.length) }
        }
        val start = i
        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
        return text.substring(start, i)
    }

    /** Skips the group opened by [open] at [i], nested groups, string literals and comments included. */
    private fun skipBalanced(open: Char, close: Char) {
        var depth = 0
        while (i < text.length) {
            when {
                text.startsWith("\"\"\"", i) -> {
                    i = text.indexOf("\"\"\"", i + 3).let { if (it < 0) text.length else it + 3 }
                    while (peek() == '"') i++
                }
                text[i] == '"' || text[i] == '\'' -> skipQuoted(text[i])
                text.startsWith("//", i) -> skipLine()
                text.startsWith("/*", i) -> skipBlockComment()
                text[i] == open -> { depth++; i++ }
                text[i] == close -> { depth--; i++; if (depth == 0) return }
                else -> i++
            }
        }
    }

    private fun skipQuoted(quote: Char) {
        i++
        while (i < text.length && text[i] != quote) {
            if (text[i] == '\\') i++
            i++
        }
        i++
    }

    private fun keyword(word: String): Boolean {
        if (!text.startsWith(word, i)) return false
        val end = i + word.length
        if (end < text.length && (text[end].isLetterOrDigit() || text[end] == '_')) return false
        i = end
        return true
    }
}
