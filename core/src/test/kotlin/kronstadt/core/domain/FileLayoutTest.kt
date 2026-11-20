package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class FileLayoutTest {
    private fun layoutOf(text: String) = FileLayout.of(TextDocument(text))

    private val fourSpaces = layoutOf("class A {\n    fun a() {\n        x()\n    }\n}\n")

    @Test
    fun `strips the code's common indent and indents it to the target column`() {
        val code = "        fun b() {\n            y()\n        }\n"
        assertEquals("    fun b() {\n        y()\n    }", fourSpaces.reindent(code, "    "))
    }

    @Test
    fun `blank lines stay empty and trailing blank lines are dropped`() {
        val code = "fun b() {\n  \n    y()\n}\n\n\n"
        assertEquals("    fun b() {\n\n        y()\n    }", fourSpaces.reindent(code, "    "))
    }

    @Test
    fun `uses the file's line endings whatever the code uses`() {
        val crlf = layoutOf("class A {\r\n    fun a() = 1\r\n}\r\n")
        assertEquals("    fun b() {\r\n        y()\r\n    }", crlf.reindent("fun b() {\n    y()\r\n}", "    "))
        assertEquals("fun b() {\n    y()\n}", fourSpaces.reindent("fun b() {\r\n    y()\r\n}", ""))
    }

    @Test
    fun `converts the code's indent unit to the file's`() {
        val tabs = layoutOf("class A {\n\tfun a() {\n\t\tx()\n\t}\n}\n")
        assertEquals("\tfun b() {\n\t\ty()\n\t}", tabs.reindent("fun b() {\n    y()\n}", "\t"))
        val twoSpaces = layoutOf("class A {\n  fun a() {\n    x()\n  }\n}\n")
        assertEquals("  fun b() {\n    y()\n  }", twoSpaces.reindent("fun b() {\n\ty()\n}", "  "))
        assertEquals("    fun b() {\n        y()\n    }", fourSpaces.reindent("fun b() {\n  y()\n}", "    "))
    }

    @Test
    fun `keeps alignment that is not a whole indent unit, such as KDoc stars`() {
        val code = "/**\n * Docs.\n */\nfun b() {\n    y()\n}"
        assertEquals("\t/**\n\t * Docs.\n\t */\n\tfun b() {\n\t\ty()\n\t}", layoutOf("class A {\n\tval a = 1\n}").reindent(code, "\t"))
    }

    @Test
    fun `a file without indented lines indents with four spaces`() {
        assertEquals("fun b() {\n    y()\n}", layoutOf("fun a() = 1\n").reindent("fun b() {\n\ty()\n}", ""))
    }

    @Test
    fun `lines inside a multi-line raw string don't count towards the file's indent unit`() {
        val file = "private const val USAGE = \"\"\"Usage:\n\n  --projects <dir>\n  --include <dir>\"\"\"\n\nfun main() {\n    run()\n}\n"
        assertEquals("    ", layoutOf(file).indentUnit)
    }

    @Test
    fun `lines inside a block comment don't count towards the file's indent unit`() {
        val file = "/*\n  Licensed under the\n  Apache License.\n*/\nfun main() {\n    run()\n}\n"
        assertEquals("    ", layoutOf(file).indentUnit)
    }

    @Test
    fun `lines inside a multi-line raw string don't count towards the code's indent unit`() {
        val code = "fun usage() {\n    print(\n        \"\"\"Usage:\n  --projects <dir>\"\"\",\n    )\n}"
        assertEquals(code, fourSpaces.reindent(code, ""))
    }

    @Test
    fun `lines inside a multi-line raw string are kept exactly as sent while the code around them moves`() {
        val code = "    fun usage() = print(\n        \"\"\"Usage:  \n  --projects <dir>  \n   \n\t--tab\"\"\" + x,\n    )\n"
        val expected = "\tfun usage() = print(\n\t\t\"\"\"Usage:  \n  --projects <dir>  \n   \n\t--tab\"\"\" + x,\n\t)"
        assertEquals(expected, layoutOf("class A {\n\tval a = 1\n}").reindent(code, "\t"))
    }

    @Test
    fun `a column is inside a line's indent after some of its spaces and tabs, not none or all of them`() {
        assertEquals(listOf(false, true, true, true, false, false), (0..5).map { FileLayout.isInsideIndent("  \t x", it) })
        assertEquals(true, FileLayout.isInsideIndent("    ", 2))
        assertEquals(false, FileLayout.isInsideIndent("", 0))
    }
}
