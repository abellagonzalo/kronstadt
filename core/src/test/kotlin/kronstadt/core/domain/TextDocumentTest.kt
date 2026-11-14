package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TextDocumentTest {
    private fun pos(line: Int, character: Int) = Position(line, character)

    @Test
    fun `converts positions to offsets with LF endings`() {
        val doc = TextDocument("ab\ncd\n")
        assertEquals(3, doc.lineCount) // trailing newline opens an empty last line
        assertEquals(0, doc.offsetOf(pos(0, 0)))
        assertEquals(3, doc.offsetOf(pos(1, 0)))
        assertEquals(6, doc.offsetOf(pos(2, 0)))
    }

    @Test
    fun `treats CRLF as a single line terminator`() {
        val doc = TextDocument("ab\r\ncd")
        assertEquals(2, doc.lineCount)
        assertEquals(4, doc.offsetOf(pos(1, 0)))
        assertEquals("ab", doc.line(0))
        assertEquals("cd", doc.line(1))
    }

    @Test
    fun `treats a lone CR as a line terminator`() {
        val doc = TextDocument("ab\rcd")
        assertEquals(2, doc.lineCount)
        assertEquals("cd", doc.line(1))
    }

    @Test
    fun `clamps a character past the line end to the line end, never into the terminator`() {
        val doc = TextDocument("ab\r\ncd")
        assertEquals(2, doc.offsetOf(pos(0, 99)))
        assertEquals(6, doc.offsetOf(pos(1, 99)))
    }

    @Test
    fun `counts characters in UTF-16 code units`() {
        val doc = TextDocument("val s = \"😀\"\nx") // emoji is a surrogate pair: 2 code units
        assertEquals(12, doc.line(0).length)
        assertEquals(13, doc.offsetOf(pos(1, 0)))
    }

    @Test
    fun `rejects lines beyond the document`() {
        assertFailsWith<IllegalArgumentException> { TextDocument("a").offsetOf(pos(1, 0)) }
    }

    @Test
    fun `positionOf is the inverse of offsetOf`() {
        val doc = TextDocument("fun a() {\r\n    b()\n}\r\n")
        for (offset in 0..doc.text.length) {
            val p = doc.positionOf(offset)
            // Offsets inside a CRLF pair map to a character past the line content, which clamps back; skip those.
            if (p.character <= doc.line(p.line).length) assertEquals(offset, doc.offsetOf(p), "offset $offset -> $p")
        }
    }

    @Test
    fun `replaces a multi-line range and preserves surrounding text exactly`() {
        val doc = TextDocument("class A {\r\n    fun old() {\r\n        1\r\n    }\r\n}\r\n")
        val range = TextRange(pos(1, 4), pos(3, 5))
        assertEquals("fun old() {\r\n        1\r\n    }", doc.slice(range))

        val updated = doc.replace(range, "fun new() = 2")
        assertEquals("class A {\r\n    fun new() = 2\r\n}\r\n", updated.text)
    }

    @Test
    fun `inserting at an empty range does not remove anything`() {
        val doc = TextDocument("a\nb")
        assertEquals("a\nxb", doc.replace(TextRange(pos(1, 0), pos(1, 0)), "x").text)
    }
}
