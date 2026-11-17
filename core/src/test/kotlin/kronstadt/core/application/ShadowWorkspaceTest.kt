package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.TextRange
import java.io.IOException
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** The Shadow Workspace as the Edit engine: several Operations over several files, one Validation, one Write-back. */
class ShadowWorkspaceTest {
    private val root = Path.of("/project")
    private val a = "src/A.kt"
    private val b = "src/B.kt"

    private fun range(line: Int, startChar: Int, endChar: Int) = TextRange(Position(line, startChar), Position(line, endChar))

    /** A function `fun <name>(): Int = …` in [text], declared from [line] to the end of [lastLine]. */
    private fun function(text: String, name: String, line: Int, lastLine: Int = line) = CodeSymbol(
        name,
        SymbolKind.FUNCTION,
        TextRange(Position(line, 0), Position(lastLine, text.lines()[lastLine].length)),
        range(line, 4, 4 + name.length),
        emptyList(),
        "(): Int",
    )

    private fun error(line: Int, message: String) = Diagnostic(range(line, 0, 1), Severity.ERROR, message, "ERR")

    private val source = """
        |package a
        |
        |fun one(): Int = 1
        |
        |fun four(): Int = 4
        |""".trimMargin()
    private val sourceSymbols = listOf(function(source, "one", 2), function(source, "four", 4))

    private fun path(text: String) = SymbolPath.parse(text).getOrThrow()

    /** Runs [block] on a Shadow Workspace over [files], in one session that must close everything it opened. */
    private fun shadow(
        files: FakeFileStore,
        intelligence: FakeCodeIntelligence,
        block: suspend ShadowWorkspace.() -> Unit,
    ) = runTest {
        val resolver = SymbolResolver(files, intelligence, 30.seconds)
        intelligence.session { session -> ShadowWorkspace(files, session, resolver, 15.seconds).block() }
        intelligence.assertOneSessionClosedEverything()
    }

    private suspend fun ShadowWorkspace.replace(file: String, symbol: String, code: String) {
        val found = assertIs<ShadowWorkspace.Lookup.Found>(resolve(root.resolve(file), path(symbol)), symbol)
        replaceLines(root.resolve(file), found.span.firstLine..found.span.lastLine, code.lines())
    }

    @Test
    fun `a later Operation resolves against the text the earlier ones left`() {
        val renamed = source.replace("fun one(): Int = 1", "fun two(): Int = 2")
        val files = FakeFileStore(root, mapOf(a to source))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(source to sourceSymbols, renamed to listOf(function(renamed, "two", 2), function(renamed, "four", 4))),
        )

        shadow(files, intelligence) {
            replace(a, "one", "fun two(): Int = 2")

            val found = assertIs<ShadowWorkspace.Lookup.Found>(resolve(root.resolve(a), path("two")))
            assertEquals("two", found.canonical)
            assertEquals(2..2, found.span.firstLine..found.span.lastLine)
            assertIs<ShadowWorkspace.Lookup.RemovedEarlier>(resolve(root.resolve(a), path("one")))
            assertIs<ShadowWorkspace.Lookup.NotFound>(resolve(root.resolve(a), path("three")))
        }
    }

    @Test
    fun `Validation looks at every edited range of a file, shifted by later splices above them`() {
        val bottomEdited = source.replace("fun four(): Int = 4", "fun four(): Int = 44")
        val bothEdited = bottomEdited.replace("fun one(): Int = 1", "fun one(): Int =\n    1")
        val mismatch = "Type mismatch"
        val files = FakeFileStore(root, mapOf(a to source))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                source to sourceSymbols,
                bottomEdited to listOf(function(bottomEdited, "one", 2), function(bottomEdited, "four", 4)),
                bothEdited to listOf(function(bothEdited, "one", 2, lastLine = 3), function(bothEdited, "four", 5)),
            ),
            // Two more mismatches than before. Line 4 (the blank line `four` was pushed below) isn't edited;
            // lines 3 and 5 are, so those are the new ones.
            diagnosticsByText = mapOf(
                source to listOf(error(0, mismatch), error(1, mismatch)),
                bothEdited to listOf(error(0, mismatch), error(3, mismatch), error(4, mismatch), error(5, mismatch)),
            ),
        )

        shadow(files, intelligence) {
            replace(a, "four", "fun four(): Int = 44")
            replace(a, "one", "fun one(): Int =\n    1")

            val rejected = assertIs<ShadowWorkspace.Validated.Rejected>(validate())
            assertEquals(listOf(4, 6), rejected.newErrors.map { it.line })
            assertEquals(listOf("one", "four"), rejected.newErrors.map { it.symbol })
        }
        assertEquals(emptyList(), files.written)
    }

    private val other = """
        |package b
        |
        |fun one(): Int = 1
        |""".trimMargin()
    private val otherEdited = other.replace("= 1", "= \"1\"")
    private val aEdited = source.replace("fun one(): Int = 1", "fun one(): Int = \"1\"")

    private fun twoFiles(diagnosticsByText: Map<String, List<Diagnostic>> = emptyMap()) = FakeCodeIntelligence(
        symbolsByText = mapOf(
            source to sourceSymbols,
            aEdited to listOf(function(aEdited, "one", 2), function(aEdited, "four", 4)),
            other to listOf(function(other, "one", 2)),
            otherEdited to listOf(function(otherEdited, "one", 2)),
        ),
        diagnosticsByText = diagnosticsByText,
    )

    private suspend fun ShadowWorkspace.editBoth() {
        replace(a, "one", "fun one(): Int = \"1\"")
        replace(b, "one", "fun one(): Int = \"1\"")
    }

    @Test
    fun `one Validation reports the errors introduced in every touched file`() {
        val files = FakeFileStore(root, mapOf(a to source, b to other))
        val intelligence = twoFiles(mapOf(aEdited to listOf(error(2, "Type mismatch")), otherEdited to listOf(error(2, "Type mismatch"))))

        shadow(files, intelligence) {
            editBoth()

            val rejected = assertIs<ShadowWorkspace.Validated.Rejected>(validate())
            assertEquals(listOf(a to 3, b to 3), rejected.newErrors.map { it.file to it.line })
        }
    }

    @Test
    fun `a Validation that passes reports each touched file, in the order the Edit touched them`() {
        val files = FakeFileStore(root, mapOf(a to source, b to other))

        shadow(files, twoFiles()) {
            replace(b, "one", "fun one(): Int = \"1\"")
            replace(a, "one", "fun one(): Int = \"1\"")

            val passed = assertIs<ShadowWorkspace.Validated.Passed>(validate())
            assertEquals(listOf(b, a), passed.files.map { it.file })
            assertEquals(listOf(EditedSymbol("one", 3, 3)), passed.files.first().symbols)
        }
    }

    @Test
    fun `Write-back writes every touched file, then tells the code intelligence each is saved`() {
        val files = FakeFileStore(root, mapOf(a to source, b to other))
        val intelligence = twoFiles()

        shadow(files, intelligence) {
            editBoth()
            assertIs<ShadowWorkspace.Validated.Passed>(validate())

            assertEquals(ShadowWorkspace.WriteBack.Written, writeBack())
        }
        assertEquals(listOf(a to aEdited, b to otherEdited), files.written)
        assertEquals(listOf("save A.kt", "save B.kt"), intelligence.events.filter { it.startsWith("save") })
    }

    @Test
    fun `a file changed on disk, whichever of the two it is, means neither is written`() {
        for (changed in listOf(a, b)) {
            val files = FakeFileStore(root, mapOf(a to source, b to other))
            val intelligence = twoFiles()

            shadow(files, intelligence) {
                editBoth()
                files.writeOutside(changed, "// theirs\n")

                assertEquals(ShadowWorkspace.WriteBack.ChangedOnDisk(changed), writeBack(), changed)
            }
            assertEquals(emptyList(), files.written, changed)
            assertEquals(emptyList(), intelligence.events.filter { it.startsWith("save") }, changed)
        }
    }

    @Test
    fun `a write that fails after another succeeded is a partial Write-back naming the file already written`() {
        val files = FakeFileStore(root, mapOf(a to source, b to other), failingWrites = setOf(b))
        val intelligence = twoFiles()

        shadow(files, intelligence) {
            editBoth()

            val partial = assertIs<ShadowWorkspace.WriteBack.Partial>(writeBack())
            assertEquals(listOf(a), partial.written)
            assertEquals(b, partial.failed)
        }
        assertEquals(listOf(a to aEdited), files.written)
        assertEquals(listOf("save A.kt"), intelligence.events.filter { it.startsWith("save") })
    }

    @Test
    fun `a write that fails before any other succeeded fails the Write-back, with nothing written`() {
        val files = FakeFileStore(root, mapOf(a to source, b to other), failingWrites = setOf(a))
        val intelligence = twoFiles()

        shadow(files, intelligence) {
            editBoth()

            assertFailsWith<IOException> { writeBack() }
        }
        assertEquals(emptyList(), files.written)
        assertEquals(emptyList(), intelligence.events.filter { it.startsWith("save") })
    }
}
