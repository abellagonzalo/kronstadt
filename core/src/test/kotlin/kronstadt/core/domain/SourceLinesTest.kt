package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceLinesTest {
    private fun rawStringLines(vararg lines: String) = SourceLines.of(lines.joinToString("\n")).rawString

    @Test
    fun `the lines after the one a raw string opens on, up to the one it closes on, are inside it`() {
        assertEquals(setOf(1, 2), rawStringLines("val a = \"\"\"one", "two", "three\"\"\" + b", "val c = 1"))
    }

    @Test
    fun `triple quotes in an ordinary string, a char literal or a comment open no raw string`() {
        assertEquals(
            emptySet(),
            rawStringLines(
                "val a = \"\\\"\\\"\\\"\" + '\"' + \"x\" // \"\"\"",
                "/* \"\"\"",
                "   /* nested */ \"\"\" */",
                "val b = 1",
            ),
        )
    }

    @Test
    fun `a raw string nested in a template of another keeps the lines inside both`() {
        assertEquals(
            setOf(1, 2),
            rawStringLines("val a = \"\"\"a \${ \"\"\"b", "c\"\"\" } d", "e\"\"\"", "val f = \"\${ \"x\" }\""),
        )
    }

    @Test
    fun `a raw string ending in more than three quotes closes at the last three`() {
        assertEquals(setOf(1), rawStringLines("val a = \"\"\"x", "y\"\"\"\"", "val b = 1"))
    }

    @Test
    fun `a comment opener inside a raw string is part of the string`() {
        assertEquals(setOf(1, 3), rawStringLines("val a = \"\"\"/*", "x\"\"\"", "val b = \"\"\"y", "z */\"\"\""))
    }

    @Test
    fun `the lines after the one a block comment opens on, up to the one it closes on, are inside it, nested ones too`() {
        val text = listOf("/**", " * Docs /* nested", " */ still inside", " */", "fun a() = 1 // /*", "val b = 2").joinToString("\n")
        assertEquals(setOf(1, 2, 3), SourceLines.of(text).blockComment)
    }
}
