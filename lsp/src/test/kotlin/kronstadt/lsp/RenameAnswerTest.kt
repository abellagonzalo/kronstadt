package kronstadt.lsp

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.FileChange
import kronstadt.core.domain.Position
import kronstadt.core.domain.Rename
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RenameAnswerTest {
    private val main = Path.of("/project/src/b/Main.kt")
    private val greeter = Path.of("/project/src/a/Greeter.kt")
    private val host = Path.of("/project/src/a/Host.kt")

    private val mainText = """
        |package b
        |
        |import a.Greeter
        |import a.Unused
        |
        |fun main() {
        |    println(Greeter().greet())
        |}
        |""".trimMargin()
    private val greeterText = "package a\n\nclass Greeter\n"

    private fun name(line: Int, char: Int, length: Int) = TextRange(Position(line, char), Position(line, char + length))

    private suspend fun read(vararg changes: ReportedChange, newName: String = "Greatest"): Rename =
        RenameAnswer.read(ReportedRename.Changes(changes.toList()), "Greeter", newName) {
            mapOf(main to TextDocument(mainText), greeter to TextDocument(greeterText))[it]
        }

    @Test
    fun `several diffs on one name make one occurrence`() = runTest {
        // Greeter -> Greatest arrives as two edits per name: its 4th letter 'e' -> 'a' and its 7th 'r' -> 'st'.
        fun split(line: Int, char: Int) = listOf(TextEdit(name(line, char + 3, 1), "a"), TextEdit(name(line, char + 6, 1), "st"))

        val answer = read(ReportedChange.Edited(main, split(2, 9) + split(6, 12)))

        assertEquals(Rename.Changes(listOf(FileChange.Edited(main, listOf(name(2, 9, 7), name(6, 12, 7))))), answer)
    }

    @Test
    fun `an insertion right after a name changes it`() = runTest {
        val answer = read(ReportedChange.Edited(greeter, listOf(TextEdit(name(2, 13, 0), "s"))), newName = "Greeters")

        assertEquals(Rename.Changes(listOf(FileChange.Edited(greeter, listOf(name(2, 6, 7))))), answer)
    }

    @Test
    fun `an edit that removes an import line is left out`() = runTest {
        val answer = read(
            ReportedChange.Edited(
                main,
                listOf(TextEdit(name(2, 9, 7), "Host"), TextEdit(TextRange(Position(3, 0), Position(4, 0)), ""), TextEdit(name(6, 12, 7), "Host")),
            ),
            newName = "Host",
        )

        assertEquals(Rename.Changes(listOf(FileChange.Edited(main, listOf(name(2, 9, 7), name(6, 12, 7))))), answer)
    }

    @Test
    fun `a moved file is reported, and an edit naming its new path is read against its old text`() = runTest {
        val answer = read(
            ReportedChange.Edited(greeter, listOf(TextEdit(name(2, 6, 7), "Host"))),
            ReportedChange.Moved(greeter, host),
            ReportedChange.Edited(main, listOf(TextEdit(name(2, 9, 7), "Host"))),
            newName = "Host",
        )

        assertEquals(
            Rename.Changes(
                listOf(
                    FileChange.Edited(greeter, listOf(name(2, 6, 7))),
                    FileChange.Moved(greeter, host),
                    FileChange.Edited(main, listOf(name(2, 9, 7))),
                ),
            ),
            answer,
        )
        assertEquals(
            Rename.Changes(listOf(FileChange.Moved(greeter, host), FileChange.Edited(host, listOf(name(2, 6, 7))))),
            read(ReportedChange.Moved(greeter, host), ReportedChange.Edited(host, listOf(TextEdit(name(2, 6, 7), "Host"))), newName = "Host"),
        )
    }

    @Test
    fun `an edit that is neither a change to the old name nor an import line's removal refuses the rename, saying what it was`() = runTest {
        val answer = read(
            ReportedChange.Edited(main, listOf(TextEdit(name(2, 9, 7), "Host"), TextEdit(name(6, 22, 5), "hello"))),
            newName = "Host",
        )

        val reason = assertIs<Rename.Refused>(answer).reason
        assertTrue("Main.kt line 7" in reason && "`greet` -> `hello`" in reason, reason)
    }

    @Test
    fun `an edit that adds a line refuses the rename too`() = runTest {
        val answer = read(ReportedChange.Edited(greeter, listOf(TextEdit(name(2, 6, 7), "Host"), TextEdit(name(3, 0, 0), "val x = 1\n"))), newName = "Host")

        val reason = assertIs<Rename.Refused>(answer).reason
        assertTrue("`` -> `val x = 1\n`" in reason, reason)
    }

    @Test
    fun `a name starting with a letter beyond ASCII is matched as a whole word`() = runTest {
        val file = Path.of("/project/src/Anger.kt")
        val text = "class Ärger\nval x = Ärger()\n"

        val answer = RenameAnswer.read(
            ReportedRename.Changes(listOf(ReportedChange.Edited(file, listOf(TextEdit(name(0, 6, 1), "A"), TextEdit(name(1, 8, 1), "A"))))),
            "Ärger",
            "Arger",
        ) { TextDocument(text) }

        assertEquals(Rename.Changes(listOf(FileChange.Edited(file, listOf(name(0, 6, 5), name(1, 8, 5))))), answer)
    }

    @Test
    fun `a blank line away from the imports, or a line that only looks like an import, can't be deleted`() = runTest {
        val file = Path.of("/project/src/Text.kt")
        val text = "package a\n\nclass Greeter\n\nval s = \"\"\"\nimport b.C\n\"\"\"\n"
        suspend fun deleting(line: Int) = RenameAnswer.read(
            ReportedRename.Changes(
                listOf(ReportedChange.Edited(file, listOf(TextEdit(name(2, 6, 7), "Host"), TextEdit(TextRange(Position(line, 0), Position(line + 1, 0)), "")))),
            ),
            "Greeter",
            "Host",
        ) { TextDocument(text) }

        assertIs<Rename.Refused>(deleting(3))
        assertIs<Rename.Refused>(deleting(5))
    }

    @Test
    fun `a file kotlin-lsp would change that doesn't exist refuses the rename`() = runTest {
        val missing = Path.of("/project/src/Gone.kt")

        assertEquals(Rename.Refused("kotlin-lsp would change $missing, which doesn't exist"), read(ReportedChange.Edited(missing, emptyList())))
    }

    @Test
    fun `the old name is the identifier at the position, or the name inside the backticks it starts`() {
        val document = TextDocument("fun `two words`() = greet(x)\n")

        assertEquals("two words", RenameAnswer.nameAt(document, Position(0, 5)))
        assertEquals("greet", RenameAnswer.nameAt(document, Position(0, 20)))
        assertEquals("greet", RenameAnswer.nameAt(document, Position(0, 22)))
        assertEquals(null, RenameAnswer.nameAt(document, Position(0, 18)))
    }
}
