package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Adding imports to a file: missing ones only, each by its closest neighbours, nothing existing moved or removed. */
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
    fun `without a package line or file annotations, a new block goes after the leading comments a blank line ends`() {
        assertEquals(
            "/* Licence */\n\nimport b.B\n\nfun f() = 1\n",
            adding("/* Licence */\n\nfun f() = 1\n", "b.B"),
        )
        assertEquals(
            "// Licence\n// line two\n\nimport b.B\n\nfun f() = 1\n",
            adding("// Licence\n// line two\n\nfun f() = 1\n", "b.B"),
        )
        assertEquals(
            "/* Licence */\n\n// Notes\n\nimport b.B\n\nfun f() = 1\n",
            adding("/* Licence */\n\n// Notes\n\nfun f() = 1\n", "b.B"),
        )
        assertEquals("/* Licence */\n\nimport b.B\n", adding("/* Licence */\n", "b.B"))
    }

    @Test
    fun `a comment attached to the first declaration, or a KDoc, stays below a new block`() {
        assertEquals(
            "import b.B\n\n// Says one\nfun f() = 1\n",
            adding("// Says one\nfun f() = 1\n", "b.B"),
        )
        assertEquals(
            "/* Licence */\n\nimport b.B\n\n// Says one\nfun f() = 1\n",
            adding("/* Licence */\n\n// Says one\nfun f() = 1\n", "b.B"),
        )
        assertEquals(
            "import b.B\n\n/** Says one */\n\nfun f() = 1\n",
            adding("/** Says one */\n\nfun f() = 1\n", "b.B"),
        )
        assertEquals(
            "/* Licence */\n\nimport b.B\n\n/** Says one */\nfun f() = 1\n",
            adding("/* Licence */\n\n/** Says one */\nfun f() = 1\n", "b.B"),
        )
    }

    @Test
    fun `after a shebang, a new block goes after the leading comments a blank line ends`() {
        assertEquals(
            "#!/usr/bin/env kotlin\n/* Licence */\n\nimport b.B\n\nprintln(1)\n",
            adding("#!/usr/bin/env kotlin\n/* Licence */\n\nprintln(1)\n", "b.B"),
        )
        assertEquals(
            "#!/usr/bin/env kotlin\n\nimport b.B\n\n// Says one\nprintln(1)\n",
            adding("#!/usr/bin/env kotlin\n\n// Says one\nprintln(1)\n", "b.B"),
        )
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
    fun `a missing import goes by the existing one sharing the longest package prefix, in whole segments`() {
        val source = "package a\n\nimport kotlinx.coroutines.flow.first\nimport kronstadt.core.X\nimport kotlin.time.Duration\n"

        assertEquals(
            "package a\n\nimport kotlinx.coroutines.channels.BufferOverflow\nimport kotlinx.coroutines.flow.first\nimport kronstadt.core.X\nimport kotlin.time.Duration\n",
            adding(source, "kotlinx.coroutines.channels.BufferOverflow"),
        )
        assertEquals(
            "package a\n\nimport kotlinx.coroutines.flow.MutableSharedFlow\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.first\nimport kronstadt.core.X\nimport kotlin.time.TimeSource\n",
            adding(
                "package a\n\nimport kotlinx.coroutines.flow.MutableStateFlow\nimport kotlinx.coroutines.flow.first\nimport kronstadt.core.X\nimport kotlin.time.TimeSource\n",
                "kotlinx.coroutines.flow.MutableSharedFlow",
            ),
        )
        assertEquals(
            "package a\n\nimport kronstadt.core.domain.FileHeader\nimport kronstadt.core.domain.FileLayout\nimport java.nio.file.Path\nimport kotlin.time.Duration\n",
            adding(
                "package a\n\nimport kronstadt.core.domain.FileLayout\nimport java.nio.file.Path\nimport kotlin.time.Duration\n",
                "kronstadt.core.domain.FileHeader",
            ),
        )
    }

    @Test
    fun `a missing import sharing no segment goes into its group of IntelliJ's default layout`() {
        val source = "package a\n\nimport b.B\nimport java.io.File\nimport kotlin.time.Duration\n"

        assertEquals(
            "package a\n\nimport b.B\nimport java.io.File\nimport javax.inject.Inject\nimport kotlin.time.Duration\n",
            adding(source, "javax.inject.Inject"),
        )
        assertEquals(
            "package a\n\nimport b.B\nimport kotlinx.io.Source\nimport java.io.File\nimport kotlin.time.Duration\n",
            adding(source, "kotlinx.io.Source"),
        )
    }

    @Test
    fun `an aliased import is only a neighbour of aliased ones, and without one goes last`() {
        val source = "package a\n\nimport b.B\nimport x.y.P as Q\nimport kotlin.time.Duration\n"

        assertEquals(
            "package a\n\nimport b.B\nimport x.y.P as Q\nimport kotlin.time.Duration\nimport kotlin.time.Instant as I\n",
            adding(source, "kotlin.time.Instant as I"),
        )
        assertEquals(
            "package a\n\nimport b.B\nimport x.y.P as Q\nimport x.y.R as S\nimport kotlin.time.Duration\n",
            adding(source, "x.y.R as S"),
        )
    }

    @Test
    fun `each missing import has the ones added before it in the same call as neighbours`() {
        assertEquals(
            "package a\n\nimport b.B\nimport kotlinx.io.Sink\nimport kotlinx.io.Source\nimport kotlin.time.Duration\n",
            adding("package a\n\nimport b.B\nimport kotlin.time.Duration\n", "kotlinx.io.Source", "kotlinx.io.Sink"),
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

    /** The imports of [text] that [organized], its text after organize-imports, found unused, sorted. */
    private fun dropped(text: String, organized: String) = TextDocument(text).importsOrganizedIn(TextDocument(organized)).dropped.map { it.text }.sorted()

    /** The imports of [text] that [organized] leaves undecided, covered by a new star import, sorted. */
    private fun undecided(text: String, organized: String) = TextDocument(text).importsOrganizedIn(TextDocument(organized)).undecided.map { it.text }.sorted()

    /** [text] without the [drop] imports, and those it removed. */
    private fun removing(text: String, vararg drop: String): Pair<String, List<String>> {
        val document = TextDocument(text)
        val removal = document.removingImports(imports(*drop).toSet()) ?: return text to emptyList()
        return document.replaceLines(removal.splice.lines, removal.splice.newLines, "\n").text to removal.removed.map { it.text }
    }

    @Test
    fun `the imports organize-imports drops are unused, except those a new star import of their package takes over, which are undecided`() {
        val text = "package a\n\nimport b.Bravo\nimport c.Charlie\nimport c.Delta\nimport c.Echo as E\nimport d.`Fox`\n\nfun f() = 1\n"
        val starred = "package a\n\nimport c.*\nimport c.Echo as E\n\nfun f() = 1\n"

        assertEquals(listOf("b.Bravo", "d.`Fox`"), dropped(text, starred))
        assertEquals(listOf("c.Charlie", "c.Delta"), undecided(text, starred))
        assertEquals(listOf("c.Echo as E"), dropped(text, "package a\n\nimport b.Bravo\nimport c.Charlie\nimport c.Delta\nimport d.Fox\n\nfun f() = 1\n"))
        assertEquals(emptyList<String>(), dropped(text, text))
        assertEquals(emptyList<String>(), undecided(text, text))
    }

    @Test
    fun `an explicit import a star import the file already had covers is dropped, not undecided`() {
        val text = "package a\n\nimport c.*\nimport c.Charlie\n\nfun f() = Charlie\n"
        val organized = "package a\n\nimport c.*\n\nfun f() = Charlie\n"

        assertEquals(listOf("c.Charlie"), dropped(text, organized))
        assertEquals(emptyList<String>(), undecided(text, organized))
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
        assertEquals(emptyList<String>(), undecided(text, "package a\n\nimport b.Bravo\n\nfun f() = Bravo\n"))
    }
}
