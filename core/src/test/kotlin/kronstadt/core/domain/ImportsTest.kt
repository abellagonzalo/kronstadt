package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Adding imports to a file: missing ones only, sorted in, nothing existing moved or removed. */
class ImportsTest {
    private fun imports(vararg raw: String) = raw.map { ImportDirective.parse(it).getOrThrow() }

    /** [text] with [raw] imports added, or [text] itself when nothing is missing. */
    private fun adding(text: String, vararg raw: String): String {
        val document = TextDocument(text)
        val splice = document.addingImports(imports(*raw)) ?: return text
        return document.replaceLines(splice.lines, splice.newLines, "\n").text
    }

    @Test
    fun `a file without imports gets a new block after the package line, sorted`() {
        val source = "package a\n\nclass X\n"

        assertEquals(
            "package a\n\nimport b.First.Inner\nimport b.Second\n\nclass X\n",
            adding(source, "b.Second", "b.First.Inner"),
        )
    }

    @Test
    fun `without a package line, a new block goes after the file annotations, else at the top`() {
        assertEquals(
            "@file:JvmName(\"X\")\n\nimport b.B\n\nfun f() = 1\n",
            adding("@file:JvmName(\"X\")\n\nfun f() = 1\n", "b.B"),
        )
        assertEquals("import b.B\n\nfun f() = 1\n", adding("fun f() = 1\n", "b.B"))
        assertEquals("package a\n\nimport b.B\n", adding("package a\n", "b.B"))
    }

    @Test
    fun `each missing import goes right after the existing one sorting just before it, leaving the others in place`() {
        // IntelliJ's layout puts java and kotlin last, so the block as a whole isn't sorted.
        val source = """
            |package a
            |
            |import b.Bravo
            |import d.Delta
            |// a comment kept in place
            |import java.io.File
            |import kotlin.time.Duration
            |
            |class X
            |""".trimMargin()

        assertEquals(
            """
            |package a
            |
            |import a.Alpha
            |import b.Bravo
            |import c.Charlie
            |import d.Delta
            |// a comment kept in place
            |import java.io.File
            |import java.util.UUID
            |import kotlin.time.Duration
            |import kotlin.time.Instant
            |
            |class X
            |""".trimMargin(),
            adding(source, "kotlin.time.Instant", "c.Charlie", "java.util.UUID", "a.Alpha"),
        )
    }

    @Test
    fun `an import line shared with the package line or another import is only placed after, never split or jumped over`() {
        assertEquals(
            "package a; import c.C\nimport a.A\n\nclass X\n",
            adding("package a; import c.C\n\nclass X\n", "a.A"),
        )
        assertEquals(
            "package a\n\nimport b.B; import d.D\nimport a.A\nimport c.C\nimport e.E\n",
            adding("package a\n\nimport b.B; import d.D\nimport e.E\n", "c.C", "a.A"),
        )
    }

    @Test
    fun `only a single dotted name, with a star or an alias, is an import directive`() {
        listOf("", "import", "a.", "a b", "*", "a.*.B", "a.* as B", "a as", "import a.B\nimport c.D").forEach {
            assertTrue(ImportDirective.parse(it).isFailure, "'$it' should be rejected")
        }
        assertEquals(listOf("a.B", "a.*", "a.B as C", "a.`my b`"), imports("a.B", " import a.*;", "a . B as C", "a.`my b`").map { it.text })
    }

    @Test
    fun `imports already there, under any spelling, or asked for twice are added once`() {
        val source = "package a\n\nimport b.`Bravo`\nimport c.*\nimport d.Delta as D\n\nclass X\n"

        assertEquals(source, adding(source, "b.Bravo", "import c.*", "d.Delta  as  D;"))
        assertEquals(
            "package a\n\nimport b.`Bravo`\nimport c.*\nimport d.Delta\nimport d.Delta as D\n\nclass X\n",
            adding(source, "d.Delta", "import d.Delta"),
        )
    }
}
