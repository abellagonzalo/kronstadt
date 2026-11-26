package kronstadt.core.application

import kronstadt.core.domain.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * [Snippet.find]: a Snippet matched exactly as written, except that any line break matches any line ending, found once or
 * missed with the lines it occurs on and, when it occurs nowhere, an indentation hint (ADR 0004).
 */
class SnippetTest {
    private fun find(text: String, snippet: String, range: IntRange = text.indices) = Snippet.find(TextDocument(text), snippet, range)

    /** A Miss of [snippet] that occurs nowhere and gets no hint. */
    private fun nowhere(snippet: String) = Snippet.Miss(snippet, 0, emptyList())

    @Test
    fun `a snippet that occurs once is found at its range`() {
        assertEquals(Snippet.Match(10..14), find("val a = 1\nval b = 2", "val b"))
    }

    @Test
    fun `a line break in the snippet matches one whole line break of any kind, in a file with mixed line endings`() {
        val text = "a\nb\r\na\rb"

        assertEquals(Snippet.Miss("a\nb", 2, listOf(SourceLine(1, "a"), SourceLine(3, "a"))), find(text, "a\nb"))
        assertEquals(Snippet.Match(2..5), find(text, "b\na"))
    }

    @Test
    fun `each kind of line break in the snippet matches each kind in the file`() {
        for (snippetBreak in listOf("\n", "\r\n", "\r")) {
            for (fileBreak in listOf("\n", "\r\n", "\r")) {
                val text = "x${fileBreak}y"
                assertEquals(Snippet.Match(text.indices), find(text, "x${snippetBreak}y"), "snippet ${snippetBreak.toList()}, file ${fileBreak.toList()}")
            }
        }
    }

    @Test
    fun `a snippet that occurs several times is missed with its count and each line it starts on, once`() {
        assertEquals(
            Snippet.Miss("val n", 3, listOf(SourceLine(1, "val n = 1"), SourceLine(3, "val n = 2; val n = 3"))),
            find("val n = 1\n\n  val n = 2; val n = 3\n", "val n"),
        )
    }

    @Test
    fun `overlapping occurrences are counted separately`() {
        assertEquals(Snippet.Miss("aa", 2, listOf(SourceLine(1, "aaa"))), find("aaa", "aa"))
        assertEquals(Snippet.Miss("a\na", 2, listOf(SourceLine(1, "a"), SourceLine(2, "a"))), find("a\r\na\r\na", "a\na"))
    }

    @Test
    fun `everything but line breaks matches exactly`() {
        assertEquals(nowhere("a \nb"), find("a\r\nb", "a \nb"))
        assertEquals(nowhere("a\nb"), find("a \r\nb", "a\nb"))
        assertEquals(nowhere("a\nb"), find("a\r\n\r\nb", "a\nb"))
    }

    @Test
    fun `a CRLF is one line break, never two, and a match never starts or ends inside it`() {
        assertEquals(Snippet.Match(1..2), find("a\r\nb", "\n"))
        assertEquals(Snippet.Match(1..3), find("a\r\nb", "\nb"))
        assertEquals(Snippet.Match(0..2), find("a\r\nb", "a\r"))
        assertEquals(nowhere("a\n\nb"), find("a\r\nb", "a\n\nb"))
        // If a "\n" could take the "\n" half of the first "\r\n", this would also occur from there, twice in all.
        assertEquals(Snippet.Match(9..15), find("package a\r\n\r\nfun", "\n\nfun"))
    }

    @Test
    fun `a match must lie within the range`() {
        assertEquals(Snippet.Match(4..6), find("a\nb a\nb", "a\nb", 1..6))
        assertEquals(nowhere("a\nb"), find("a\r\nb", "a\nb", 0..2))
    }

    private val greet = "fun greet(): Int {\n    val n = 1\n    return n\n}\n"

    /** The indentation hint of a [snippet] that occurs nowhere in [text]. */
    private fun hintOf(text: String, snippet: String): IndentationHint? {
        val miss = assertIs<Snippet.Miss>(find(text, snippet))
        assertEquals(0, miss.count)
        assertEquals(emptyList(), miss.lines)
        return miss.indentation
    }

    @Test
    fun `a snippet whose lines are each indented differently the same way gets a hint with the indents`() {
        assertEquals(IndentationHint(2, IndentationHint.Indents(line = 2, file = "    ", snippet = "  ")), hintOf(greet, "  val n = 1\n  return n"))
    }

    @Test
    fun `a snippet whose lines are indented differently in different ways gets a hint without indents`() {
        assertEquals(IndentationHint(2, null), hintOf(greet, "  val n = 1\n      return n"))
    }

    @Test
    fun `a snippet starting mid-line without indentation gets indents from its first indented line`() {
        assertEquals(IndentationHint(1, IndentationHint.Indents(line = 2, file = "    ", snippet = "  ")), hintOf(greet, "Int {\n  val n = 1"))
    }

    @Test
    fun `a snippet with LF line breaks gets the hint in a CRLF file`() {
        assertEquals(
            IndentationHint(2, IndentationHint.Indents(line = 3, file = "    ", snippet = "  ")),
            hintOf(greet.replace("\n", "\r\n"), "val n = 1\n  return n"),
        )
    }

    @Test
    fun `no hint when the snippet occurs more than once with indentation ignored, or differs in more than indentation`() {
        assertEquals(null, hintOf(greet + greet, "  val n = 1\n  return n"))
        assertEquals(null, hintOf(greet, "  val n = 2\n  return n"))
    }

    @Test
    fun `no hint when the snippet occurs exactly as written, even several times`() {
        assertEquals(null, assertIs<Snippet.Miss>(find(greet + greet, "    val n = 1")).indentation)
    }
}
