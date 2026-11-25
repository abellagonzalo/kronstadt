package kronstadt.core.domain

/**
 * Which lines of some Kotlin source start inside a multi-line raw string or a block comment, found by a small lexer
 * rather than the compiler. Line indices count from 0, as [String.lines] splits the text. [rawString] lines are
 * data: their indentation is part of the string's value. [blockComment] lines (KDoc included) are layout, but may
 * be aligned freely, so neither says what the code's indent unit is.
 */
internal data class SourceLines(val rawString: Set<Int>, val blockComment: Set<Int>) {
    /** Whether line [index] is code rather than inside a raw string or a block comment. */
    fun isCode(index: Int): Boolean = index !in rawString && index !in blockComment

    companion object {
        /** Lexes [text], which need not compile: an unterminated string or comment runs to the end. */
        fun of(text: String): SourceLines = Lexer(text).run()
    }
}

/**
 * Walks Kotlin source keeping a stack of what it is inside: code (the file itself or a `${…}` string template),
 * a raw string, an ordinary string or a block comment (they nest). Malformed source never fails: an ordinary string
 * ends at its line, and anything left open at the end stays open.
 */
private class Lexer(private val text: String) {
    private sealed interface Context
    private class Code(var braces: Int = 0) : Context
    private data object RawString : Context
    private data object QuotedString : Context
    private class BlockComment(var depth: Int = 1) : Context

    private val contexts = ArrayDeque<Context>(listOf(Code()))
    private val rawString = mutableSetOf<Int>()
    private val blockComment = mutableSetOf<Int>()
    private var line = 0
    private var i = 0

    /** The length of the [TextDocument.LINE_BREAK] starting at each offset of [text], 0 where none does. */
    private val lineBreakLengths = ByteArray(text.length).also { lengths ->
        TextDocument.LINE_BREAK.findAll(text).forEach { lengths[it.range.first] = it.value.length.toByte() }
    }

    fun run(): SourceLines {
        while (i < text.length) {
            if (lineBreak()) continue
            when (val context = contexts.last()) {
                is Code -> code(context)
                RawString -> rawString()
                QuotedString -> quotedString()
                is BlockComment -> blockComment(context)
            }
        }
        return SourceLines(rawString, blockComment)
    }

    private fun at(token: String) = text.startsWith(token, i)

    private fun atLineBreak(offset: Int = i) = offset < text.length && lineBreakLengths[offset] > 0

    /** Steps over a line break, if [i] is at one, and records what the next line starts inside. */
    private fun lineBreak(): Boolean {
        if (!atLineBreak()) return false
        i += lineBreakLengths[i]
        line++
        if (contexts.last() == QuotedString) contexts.removeLast()
        if (RawString in contexts) rawString += line else if (contexts.last() is BlockComment) blockComment += line
        return true
    }

    private fun code(context: Code) {
        when {
            at(RAW_QUOTES) -> { contexts += RawString; i += RAW_QUOTES.length }
            at("\"") -> { contexts += QuotedString; i++ }
            at("'") -> charLiteral()
            at("//") -> while (i < text.length && !atLineBreak()) i++
            at("/*") -> { contexts += BlockComment(); i += 2 }
            at("`") -> backtickName()
            at("{") -> { context.braces++; i++ }
            at("}") -> { if (context.braces == 0 && contexts.size > 1) contexts.removeLast() else context.braces--; i++ }
            else -> i++
        }
    }

    /** Steps over a `name in backticks`, which may hold quotes. */
    private fun backtickName() {
        i++
        while (i < text.length && !atLineBreak() && !at("`")) i++
        if (i < text.length && at("`")) i++
    }

    private fun charLiteral() {
        i++
        if (i < text.length && at("\\")) i++
        if (i < text.length && !atLineBreak()) i++
        while (i < text.length && !atLineBreak() && !at("'")) i++
        if (i < text.length && at("'")) i++
    }

    private fun rawString() {
        when {
            at("\"") -> {
                val quotes = text.substring(i).takeWhile { it == '"' }.length
                if (quotes >= RAW_QUOTES.length) contexts.removeLast()
                i += quotes
            }
            at("\${") -> { contexts += Code(); i += 2 }
            else -> i++
        }
    }

    private fun quotedString() {
        when {
            at("\\") -> i += if (i + 1 < text.length && !atLineBreak(i + 1)) 2 else 1
            at("\"") -> { contexts.removeLast(); i++ }
            at("\${") -> { contexts += Code(); i += 2 }
            else -> i++
        }
    }

    private fun blockComment(context: BlockComment) {
        when {
            at("/*") -> { context.depth++; i += 2 }
            at("*/") -> { if (--context.depth == 0) contexts.removeLast(); i += 2 }
            else -> i++
        }
    }

    private companion object {
        const val RAW_QUOTES = "\"\"\""
    }
}
