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

    /** The imports of [text] that [organized], its text after organize-imports, no longer has, sorted. */
    private fun dropped(text: String, organized: String) = TextDocument(text).importsDroppedIn(TextDocument(organized)).map { it.text }.sorted()

    /** [text] without the [drop] imports, and those it removed. */
    private fun removing(text: String, vararg drop: String): Pair<String, List<String>> {
        val document = TextDocument(text)
        val removal = document.removingImports(imports(*drop).toSet()) ?: return text to emptyList()
        return document.replaceLines(removal.splice.lines, removal.splice.newLines, "\n").text to removal.removed.map { it.text }
    }

    @Test
    fun `the imports organize-imports drops are unused, except those a new star import of their package takes over`() {
        val text = "package a\n\nimport b.Bravo\nimport c.Charlie\nimport c.Delta\nimport c.Echo as E\nimport d.`Fox`\n\nfun f() = 1\n"

        assertEquals(listOf("b.Bravo", "d.`Fox`"), dropped(text, "package a\n\nimport c.*\nimport c.Echo as E\n\nfun f() = 1\n"))
        assertEquals(listOf("c.Echo as E"), dropped(text, "package a\n\nimport b.Bravo\nimport c.Charlie\nimport c.Delta\nimport d.Fox\n\nfun f() = 1\n"))
        assertEquals(emptyList<String>(), dropped(text, text))
    }

    @Test
    fun `each import to drop goes with its line, the others stay in place`() {
        val text = "package a\n\nimport d.Delta\nimport b.Bravo\nimport c.Charlie\n\nfun f() = 1\n"

        assertEquals("package a\n\nimport d.Delta\nimport c.Charlie\n\nfun f() = 1\n" to listOf("b.Bravo"), removing(text, "b.Bravo", "x.NotThere"))
        assertEquals(text to emptyList(), removing(text, "x.NotThere"))
    }

    @Test
    fun `an emptied import list leaves no blank line where it was, and an emptied group only one`() {
        assertEquals("package a\n\nfun f() = 1\n" to listOf("b.Bravo"), removing("package a\n\nimport b.Bravo\n\nfun f() = 1\n", "b.Bravo"))
        assertEquals("package a\n" to listOf("b.Bravo"), removing("package a\n\nimport b.Bravo\n", "b.Bravo"))
        assertEquals("fun f() = 1\n" to listOf("b.Bravo"), removing("import b.Bravo\n\nfun f() = 1\n", "b.Bravo"))
        assertEquals(
            "package a\n\nimport b.Bravo\n\nimport java.io.File\n\nfun f() = 1\n" to listOf("c.Charlie", "c.Delta"),
            removing("package a\n\nimport b.Bravo\n\nimport c.Charlie\nimport c.Delta\n\nimport java.io.File\n\nfun f() = 1\n", "c.Charlie", "c.Delta"),
        )
    }

    @Test
    fun `blank lines that no removed import borders stay as they are`() {
        val text = "package a\n\nimport b.Bravo\n\n\nimport c.Charlie\nimport d.Delta\n\nfun f() = 1\n"

        assertEquals("package a\n\nimport b.Bravo\n\n\nimport c.Charlie\n\nfun f() = 1\n" to listOf("d.Delta"), removing(text, "d.Delta"))
    }

    @Test
    fun `an import sharing its line with another or the package line stays, and isn't reported`() {
        val text = "package a; import b.Bravo\nimport c.Charlie; import d.Delta\nimport e.Echo;\n\nfun f() = 1\n"

        assertEquals(
            "package a; import b.Bravo\nimport c.Charlie; import d.Delta\n\nfun f() = 1\n" to listOf("e.Echo"),
            removing(text, "b.Bravo", "c.Charlie", "e.Echo"),
        )
    }

    @Test
    fun `a star import organize-imports replaces with explicit imports from its package stays`() {
        val text = "package a\n\nimport b.*\nimport c.*\n\nfun f() = Bravo\n"

        assertEquals(listOf("c.*"), dropped(text, "package a\n\nimport b.Bravo\n\nfun f() = Bravo\n"))
    }
}
