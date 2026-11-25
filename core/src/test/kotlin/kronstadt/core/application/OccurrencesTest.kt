package kronstadt.core.application

import kronstadt.core.domain.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/** [occurrences]: a snippet matched exactly as written, except that any line break matches any line break. */
class OccurrencesTest {
    private fun occurrences(text: String, snippet: String, range: IntRange = text.indices) =
        occurrences(TextDocument(text), snippet, range)

    @Test
    fun `a line break in the snippet matches one whole line break of any kind, in a file with mixed line endings`() {
        val text = "a\nb\r\na\rb"

        assertEquals(listOf(0..2, 5..7), occurrences(text, "a\nb"))
        assertEquals(listOf(2..5), occurrences(text, "b\na"))
    }

    @Test
    fun `each kind of line break in the snippet matches each kind in the file`() {
        for (snippetBreak in listOf("\n", "\r\n", "\r")) {
            assertEquals(listOf(0..2, 4..7, 9..11), occurrences("x\ny x\r\ny x\ry", "x${snippetBreak}y"), "snippet break ${snippetBreak.toList()}")
        }
    }

    @Test
    fun `overlapping occurrences are counted separately`() {
        assertEquals(listOf(0..1, 1..2), occurrences("aaa", "aa"))
        assertEquals(listOf(0..3, 3..6), occurrences("a\r\na\r\na", "a\na"))
    }

    @Test
    fun `everything but line breaks matches exactly`() {
        assertEquals(emptyList(), occurrences("a\r\n    b", "a\n  b"))
        assertEquals(emptyList(), occurrences("a \r\nb", "a\nb"))
        assertEquals(emptyList(), occurrences("a\r\n\r\nb", "a\nb"))
    }

    @Test
    fun `a CRLF is one line break, never two, and an occurrence never starts or ends inside it`() {
        assertEquals(listOf(1..2), occurrences("a\r\nb", "\n"))
        assertEquals(listOf(1..3), occurrences("a\r\nb", "\nb"))
        assertEquals(listOf(0..2), occurrences("a\r\nb", "a\r"))
        assertEquals(emptyList(), occurrences("a\r\nb", "a\n\nb"))
    }

    @Test
    fun `an occurrence must lie within the range`() {
        assertEquals(listOf(4..6), occurrences("a\nb a\nb", "a\nb", 1..6))
        assertEquals(emptyList(), occurrences("a\r\nb", "a\nb", 0..2))
    }
}
