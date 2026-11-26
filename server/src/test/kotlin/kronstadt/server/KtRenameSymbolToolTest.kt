package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.FileChange
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.TextRange
import kronstadt.core.port.AnalysisSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** The kt_rename_symbol tool's arguments as an agent sends them, through to its text and the files on disk. */
class KtRenameSymbolToolTest {
    private val root: Path = Files.createTempDirectory("kt-rename-tool").toRealPath()
    private val a = root.resolve("A.kt").apply { writeText("package a\n\nfun greet(): Int = 1\n") }
    private val b = root.resolve("B.kt").apply { writeText("package b\n\nimport a.greet\n\nval x = greet()\n") }

    private val tools = ToolHarness(root, RenamingIntelligence(a, b))

    private suspend fun call(tool: String, arguments: String): Pair<Boolean, String> = tools.call(tool, arguments)

    @Test
    fun `a rename reports the new path and each changed file with its Usages and imports`() = runTest {
        val (failed, text) = call("kt_rename_symbol", """{"symbol": "greet", "newName": "hello", "file": "A.kt"}""")

        assertEquals(false, failed, text)
        assertEquals(
            "Renamed greet to hello: 1 usage in 1 file (+1 import), and the declaration. New path: hello\n" +
                "  A.kt: declaration\n" +
                "  B.kt: 1 usage, 1 import",
            text,
        )
        assertEquals("package a\n\nfun hello(): Int = 1\n", a.readText())
        assertEquals("package b\n\nimport a.hello\n\nval x = hello()\n", b.readText())
    }

    @Test
    fun `a new name that isn't an identifier is an error naming it`() = runTest {
        val (failed, text) = call("kt_rename_symbol", """{"symbol": "greet", "newName": "say hi", "file": "A.kt"}""")

        assertEquals(true, failed)
        assertEquals("'say hi' is not a Kotlin identifier; pass a plain name, or one in backticks. Nothing written.", text)
    }

    @Test
        fun `a one-line lookup failure is followed by Nothing written after a space`() = runTest {
            val (failed, text) = call("kt_rename_symbol", """{"symbol": "nope", "newName": "hello", "file": "A.kt"}""")

            assertEquals(true, failed)
            assertEquals("No symbol 'nope' in A.kt. Use kt_read_structure to list its declarations. Nothing written.", text)
        }

    @Test
    fun `a lookup failure that ends on a list is followed by Nothing written on its own line`() = runTest {
        val (failed, text) = call("kt_rename_symbol", """{"symbol": "gree", "newName": "hello", "file": "A.kt"}""")

        assertEquals(true, failed)
        assertEquals("No symbol 'gree' in A.kt. Close names:\n  greet\nNothing written.", text)
    }

    @Test
    fun `a rename can't be an Operation of a kt_edit batch`() = runTest {
        val (failed, text) = call("kt_edit", """{"operations": [{"type": "rename", "symbol": "greet", "file": "A.kt"}]}""")

        assertEquals(true, failed)
        assertEquals(
            "Operation 0: unsupported type 'rename'; use replace, insert_before, insert_after, delete, edit_in or create, and " +
                "kt_rename_symbol for renames, on its own. Nothing written.",
            text,
        )
    }
}

/** Always ready; knows `greet` in [a] (or `hello` once renamed), and renames it there and in [b]. */
private class RenamingIntelligence(private val a: Path, private val b: Path) : UnstubbedCodeIntelligence() {
    private fun name(line: Int, char: Int, length: Int) = TextRange(Position(line, char), Position(line, char + length))

    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : UnstubbedAnalysisSession() {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> {
            if (file != a) return emptyList()
            val function = if ("greet" in document.text) "greet" else "hello"
            return listOf(CodeSymbol(function, SymbolKind.FUNCTION, TextRange(Position(2, 0), Position(2, 20)), name(2, 4, 5), parameterTypes = "", type = "(): Int"))
        }

        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> = emptyList()
        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation =
            RenamePreparation.Renameable(name(2, 4, 5), "greet")

        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename = Rename.Changes(
            listOf(
                FileChange.Edited(b, listOf(TextEdit(name(2, 9, 5), newName), TextEdit(name(4, 8, 5), newName))),
                FileChange.Edited(a, listOf(TextEdit(name(2, 4, 5), newName))),
            ),
        )

        override suspend fun unusedImports(file: Path, document: TextDocument, among: Set<ImportDirective>, budget: Duration) = emptySet<ImportDirective>()

        override suspend fun writtenBack(file: Path, document: TextDocument) = Unit
    })

    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
