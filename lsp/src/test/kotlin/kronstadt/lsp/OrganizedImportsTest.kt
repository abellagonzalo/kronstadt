package kronstadt.lsp

import kronstadt.core.domain.TextDocument
import kotlin.test.Test
import kotlin.test.assertEquals

/** Reading kotlin-lsp's organize-imports answer: which of a file's imports it dropped, and which it left undecided. */
class OrganizedImportsTest {
    /** The imports of [text] that [organized], its text after organize-imports, found unused, sorted. */
    private fun dropped(text: String, organized: String) = TextDocument(text).importsOrganizedIn(TextDocument(organized)).dropped.map { it.text }.sorted()

    /** The imports of [text] that [organized] leaves undecided, covered by a new star import, sorted. */
    private fun undecided(text: String, organized: String) = TextDocument(text).importsOrganizedIn(TextDocument(organized)).undecided.map { it.text }.sorted()

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
    fun `a star import organize-imports replaces with explicit imports from its package stays`() {
        val text = "package a\n\nimport b.*\nimport c.*\n\nfun f() = Bravo\n"

        assertEquals(listOf("c.*"), dropped(text, "package a\n\nimport b.Bravo\n\nfun f() = Bravo\n"))
        assertEquals(emptyList<String>(), undecided(text, "package a\n\nimport b.Bravo\n\nfun f() = Bravo\n"))
    }
}
