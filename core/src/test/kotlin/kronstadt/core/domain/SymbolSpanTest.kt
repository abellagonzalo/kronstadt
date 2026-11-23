package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** How replace, insert and delete lay out their lines around a Symbol Span. */
class SymbolSpanTest {
    private val document = TextDocument(
        """
        |class Greeter {
        |    fun a() = 1
        |
        |    fun b() = 2
        |
        |    fun c() = 3
        |}
        |""".trimMargin(),
    )

    private fun span(line: Int) = SymbolSpan(line, line, document.line(line))

    private fun TextDocument.with(replacement: LineReplacement) = replaceLines(replacement.lines, replacement.newLines, "\n").text

    @Test
    fun `replacedBy swaps exactly the span's lines`() {
        val span = SymbolSpan(1, 3, document.lines(1, 3))

        assertEquals(LineReplacement(1..3, listOf("    fun x() = 0")), span.replacedBy(listOf("    fun x() = 0")))
    }

    @Test
    fun `siblings go one blank line above or below the span, from their first non-empty line`() {
        val code = listOf("", "    fun x() = 0")

        assertEquals(LineReplacement(3 until 3, listOf("    fun x() = 0", "")), span(3).siblingBefore(code))
        assertEquals(LineReplacement(4 until 4, listOf("", "    fun x() = 0")), span(3).siblingAfter(code))
        assertEquals(
            "class Greeter {\n    fun a() = 1\n\n    fun x() = 0\n\n    fun b() = 2\n\n    fun c() = 3\n}\n",
            document.with(span(3).siblingBefore(code)),
        )
    }

    @Test
    fun `deletion takes the blank line below the span, else the one above, else none`() {
        assertEquals(3..4, span(3).deletion(document).lines)
        assertEquals(4..5, span(5).deletion(document).lines)
        assertEquals("class Greeter {\n}", TextDocument("class Greeter {\n    fun a() = 1\n}").let { it.with(SymbolSpan(1, 1, it.line(1)).deletion(it)) })
        assertEquals(emptyList(), span(5).deletion(document).newLines)
    }

    @Test
    fun `deletion never takes the empty line after a final newline`() {
        val file = TextDocument("package a\n\nfun a() = 1\n")

        assertEquals(1..2, SymbolSpan(2, 2, file.line(2)).deletion(file).lines)
        assertEquals("package a\n", file.with(SymbolSpan(2, 2, file.line(2)).deletion(file)))
    }
}
