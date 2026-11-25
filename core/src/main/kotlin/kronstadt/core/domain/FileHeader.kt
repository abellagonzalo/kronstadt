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
    get() = FileHeader.of(text).packageName

/**
 * What a Kotlin file's header holds, read as in the Kotlin grammar: file annotations, the package directive, then the
 * import list. Anything after the last import directive is the body.
 */
internal class FileHeader(
    val packageName: String,
    /**
     * Offset just past the package directive, else past the last file annotation, else past the last leading comment
     * a blank line follows, a KDoc-style one included (stopping at a comment attached to what follows it), else past
     * the shebang line; null if none.
     */
    val preambleEnd: Int?,
    /** The import directives in order, with the offsets of `import` and of the directive's end. */
    val imports: List<ScannedImport>,
) {
    class ScannedImport(val directive: ImportDirective, val start: Int, val end: Int)

    companion object {
        fun of(text: String): FileHeader = HeaderScanner(text).header()

        /** [text] as one import directive, with or without `import` and a trailing `;`; null if it is anything else. */
        fun directive(text: String): ImportDirective? = HeaderScanner(text).directive()
    }
}

private class HeaderScanner(private val text: String) {
    private var i = 0

    fun header(): FileHeader {
        if (peek() == '\uFEFF') i++
        var preambleEnd: Int? = null
        if (text.startsWith("#!", i)) {
            skipLine()
            preambleEnd = i
        }
        detachedCommentsEnd()?.let { preambleEnd = it }
        skipWhitespace()
        while (peek() == '@') {
            if (!skipFileAnnotation()) return FileHeader("", preambleEnd, emptyList())
            preambleEnd = i
            skipWhitespace()
        }
        var packageName = ""
        if (keyword("package")) {
            val segments = mutableListOf<String>()
            do {
                if (segments.isNotEmpty()) i++ // '.'
                skipWhitespace()
                segments += identifier()
                preambleEnd = i
                skipWhitespace()
            } while (peek() == '.')
            if (peek() == ';') {
                i++
                preambleEnd = i
            }
            packageName = segments.joinToString(".")
            skipWhitespace()
        }
        val imports = mutableListOf<FileHeader.ScannedImport>()
        while (true) {
            val start = i
            if (!keyword("import")) break
            val directive = importBody() ?: break
            imports += FileHeader.ScannedImport(directive, start, i)
            skipWhitespace()
            if (peek() == ';') {
                i++
                skipWhitespace()
            }
        }
        return FileHeader(packageName, preambleEnd, imports)
    }

    fun directive(): ImportDirective? {
        skipWhitespace()
        keyword("import")
        val directive = importBody() ?: return null
        skipWhitespace()
        if (peek() == ';') i++
        skipWhitespace()
        return directive.takeIf { i == text.length }
    }

    /**
     * Offset just past the last comment from [i] on that a blank line, or the end of the file, follows; null if none.
     * Comments with no blank line between them and the next comment or code belong to what follows, so the scan stops
     * there. A KDoc is no exception: one a blank line follows, such as a licence written in KDoc style, is detached too.
     * [i] is left where it was.
     */
    private fun detachedCommentsEnd(): Int? {
        val start = i
        var end: Int? = null
        while (true) {
            skipBlanks()
            if (!skipComment()) break
            if (blankLineFollows()) end = i
        }
        i = start
        return end
    }

    /** Skips whitespace only, comments not included. */
    private fun skipBlanks() {
        while (i < text.length && text[i].isWhitespace()) i++
    }

    /** Whether the whitespace from [i] on holds a blank line or runs to the end of the file. [i] doesn't move. */
    private fun blankLineFollows(): Boolean {
        var j = i
        while (j < text.length && text[j].isWhitespace()) j++
        return j == text.length || TextDocument.LINE_BREAK.findAll(text.substring(i, j)).count() >= 2
    }

    /**
     * The directive after `import`: a dotted name, then `.*` or `as Alias`. [i] ends just past it; null, with [i]
     * anywhere, if it is malformed.
     */
    private fun importBody(): ImportDirective? {
        val written = mutableListOf<String>()
        val names = mutableListOf<String>()
        var end: Int
        while (true) {
            skipWhitespace()
            if (peek() == '*' && names.isNotEmpty()) {
                i++
                written += "*"
                names += "*"
                end = i
                break
            }
            val start = i
            val name = identifier()
            if (name.isEmpty()) return null
            written += text.substring(start, i)
            names += name
            end = i
            skipWhitespace()
            if (peek() != '.') break
            i++
        }
        var alias: Pair<String, String>? = null
        if (names.last() != "*") {
            skipWhitespace()
            if (keyword("as")) {
                skipWhitespace()
                val start = i
                val name = identifier()
                if (name.isEmpty()) return null
                alias = text.substring(start, i) to name
                end = i
            }
        }
        i = end
        return ImportDirective(
            text = written.joinToString(".") + alias?.let { " as ${it.first}" }.orEmpty(),
            key = names.joinToString(".") + alias?.let { " as ${it.second}" }.orEmpty(),
        )
    }

    private fun peek(): Char? = text.getOrNull(i)

    /** Skips whitespace, line comments and (nested) block comments. */
    private fun skipWhitespace() {
        while (i < text.length) {
            when {
                text[i].isWhitespace() -> i++
                !skipComment() -> return
            }
        }
    }

    /** Skips the line or (nested) block comment at [i]; false, with [i] unmoved, if none starts there. */
    private fun skipComment(): Boolean {
        when {
            text.startsWith("//", i) -> skipLine()
            text.startsWith("/*", i) -> skipBlockComment()
            else -> return false
        }
        return true
    }

    private fun skipLine() {
        i = TextDocument.LINE_BREAK.find(text, i)?.range?.first ?: text.length
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
