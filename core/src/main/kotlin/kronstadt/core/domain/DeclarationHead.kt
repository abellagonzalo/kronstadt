package kronstadt.core.domain

/**
 * A declaration's contract without its body: from its first line (KDoc, annotations, modifiers) to just before the body
 * or initializer, and what the keywords before its name say. Found by text around the name, so it works on any Kotlin
 * source, a library's included.
 */
public object DeclarationHead {
    private val ACCESSOR = Regex("""^((private|protected|internal|public)\s+)?(get|set)\b""")

    private val BINDING = Regex("""\b(val|var)\b""")

    private val SEALED = Regex("""\bsealed\b""")

    private val COMMENT_OR_STRING = Regex("""/\*[\s\S]*?\*/|//[^\n]*|"(\\.|[^"\\])*"""")

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

    /**
     * Whether the property [symbol] in [document] is declared `val` ([SymbolKind.PROPERTY]) or `var`
     * ([SymbolKind.VARIABLE]), by the last of those keywords between the start of its range and its name, so none in its
     * KDoc, comments or annotations counts. Null if there is none, as for a plain constructor parameter, or if [symbol]
     * lies outside [document].
     */
    public fun propertyKind(document: TextDocument, symbol: CodeSymbol): SymbolKind? {
        val start = symbol.range.start
        val name = symbol.selectionRange.start
        if (name.line >= document.lineCount || start > name) return null
        val text = document.text.substring(document.offsetOf(start), document.offsetOf(name))
        return when (BINDING.findAll(text).lastOrNull()?.value) {
            "val" -> SymbolKind.PROPERTY
            "var" -> SymbolKind.VARIABLE
            else -> null
        }
    }

    /**
     * Where the names of the class-like [symbol]'s direct supertypes are in [document]: each name after the `:` of its
     * declaration head, at its last segment when qualified (`a.b.Base`), ignoring type arguments, constructor-call
     * parentheses, annotations and what a delegating `by` names. Empty if it lists none, or if [symbol] lies outside
     * [document].
     */
    public fun supertypes(document: TextDocument, symbol: CodeSymbol): List<Position> {
        if (symbol.range.end.line >= document.lineCount) return emptyList()
        val text = document.text
        val end = document.offsetOf(symbol.range.end)
        var i = document.offsetOf(symbol.selectionRange.end)
        var depth = 0
        var listed = false
        var expectingName = true
        val names = mutableListOf<Position>()
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
                c == '{' || c == '=' -> break
                !listed -> if (c == ':') listed = true
                c == ',' -> expectingName = true
                isWord(text, i, "where") -> break
                isWord(text, i, "by") -> {
                    expectingName = false
                    i++
                }
                c == '@' -> i = qualifiedName(text, i + 1, end).first - 1
                expectingName && Character.isJavaIdentifierStart(c) -> {
                    val (after, lastSegment) = qualifiedName(text, i, end)
                    names += document.positionOf(lastSegment)
                    expectingName = false
                    i = after - 1
                }
            }
            i++
        }
        return names
    }

    /**
     * Whether the class-like [symbol] in [document] is declared `sealed`, by its modifiers: the text between the start of
     * its range and its name, without comments or string literals, so none in its KDoc or annotations counts.
     */
    public fun isSealed(document: TextDocument, symbol: CodeSymbol): Boolean {
        val start = symbol.range.start
        val name = symbol.selectionRange.start
        if (name.line >= document.lineCount || start > name) return false
        val head = document.text.substring(document.offsetOf(start), document.offsetOf(name))
        return SEALED.containsMatchIn(head.replace(COMMENT_OR_STRING, " "))
    }

    private fun lineEnd(text: String, from: Int) = text.indexOf('\n', from).let { if (it < 0) text.length else it }

    /** Whether [word] stands alone at [at] in [text], not as part of a longer identifier. */
    private fun isWord(text: String, at: Int, word: String): Boolean =
        text.startsWith(word, at) &&
            text.getOrNull(at - 1)?.let(Character::isJavaIdentifierPart) != true &&
            text.getOrNull(at + word.length)?.let(Character::isJavaIdentifierPart) != true

    /** The end of the dotted name starting at [from], and where its last segment starts. */
    private fun qualifiedName(text: String, from: Int, end: Int): Pair<Int, Int> {
        var i = from
        var lastSegment = from
        while (i < end) {
            lastSegment = i
            while (i < end && Character.isJavaIdentifierPart(text[i])) i++
            if (i + 1 < end && text[i] == '.' && Character.isJavaIdentifierStart(text[i + 1])) i++ else break
        }
        return i to lastSegment
    }

    /** Index just past the string or char literal opening at [open]; raw strings (`"""`) included. */
    private fun skipQuoted(text: String, open: Int, end: Int): Int {
        if (text.startsWith("\"\"\"", open)) return text.indexOf("\"\"\"", open + 3).let { if (it < 0) end else it + 3 }
        val quote = text[open]
        var i = open + 1
        while (i < end && text[i] != quote) i += if (text[i] == '\\') 2 else 1
        return minOf(i + 1, end)
    }
}
