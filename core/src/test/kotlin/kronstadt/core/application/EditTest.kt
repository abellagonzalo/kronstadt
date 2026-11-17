package kronstadt.core.application

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class EditTest {
    private val root = Path.of("/project")
    private val path = "src/A.kt"

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    /** A symbol whose name sits at [nameLine]:[nameChar], as kotlin-lsp reports it. */
    private fun symbol(
        name: String,
        kind: SymbolKind,
        range: TextRange,
        nameLine: Int,
        nameChar: Int,
        detail: String? = null,
        children: List<CodeSymbol> = emptyList(),
    ) = CodeSymbol(name, kind, range, range(nameLine, nameChar, nameLine, nameChar + name.length), children, detail)

    private val source = """
        |package a
        |
        |class Greeter {
        |    /** Says hi. */
        |    fun greet(): String = "hi"
        |
        |    fun other(): Int = 1
        |}
        |""".trimMargin()
    private fun greeter(vararg members: CodeSymbol, lastLine: Int = 7) =
        symbol("Greeter", SymbolKind.CLASS, range(2, 0, lastLine, 1), 2, 6, children = members.toList())
    private val greet = symbol("greet", SymbolKind.FUNCTION, range(3, 4, 4, 30), 4, 8, "(): String")
    private val other = symbol("other", SymbolKind.FUNCTION, range(6, 4, 6, 24), 6, 8, "(): Int")

    private val hello = """
        |package a
        |
        |class Greeter {
        |    fun greet(): String =
        |        "hello"
        |
        |    fun other(): Int = 1
        |}
        |""".trimMargin()
    private val helloGreet = symbol("greet", SymbolKind.FUNCTION, range(3, 4, 4, 15), 3, 8, "(): String")

    private fun error(line: Int, char: Int, message: String, code: String = "ERR") =
        Diagnostic(range(line, char, line, char + 1), Severity.ERROR, message, code)

    private fun edit(
        intelligence: FakeCodeIntelligence,
        files: FakeFileStore = FakeFileStore(root, mapOf(path to source)),
    ) = Edit(files, intelligence)

    private fun intelligence(
        diagnosticsByText: Map<String, List<Diagnostic>> = emptyMap(),
        symbolsByText: Map<String, List<CodeSymbol>> = mapOf(source to listOf(greeter(greet, other)), hello to listOf(greeter(helloGreet, other))),
    ) = FakeCodeIntelligence(symbolsByText = symbolsByText, diagnosticsByText = diagnosticsByText)

    @Test
    fun `a replace that introduces no errors is re-indented, written back and reported by its resulting Symbol Paths`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))

        val result = edit(intelligence(), files)(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\"\n"))

        assertEquals(hello, files.content(path))
        val written = assertIs<EditResult.Written>(result).files.single()
        assertEquals(EditedFile(path, listOf(EditedSymbol("greet", 4, 5)), emptyList(), 0, 0), written)
    }

    @Test
    fun `a replace that introduces an error is rejected with where it is and what it says, and nothing is written`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = intelligence(diagnosticsByText = mapOf(hello to listOf(error(4, 8, "Type mismatch: inferred type is String"))))

        val result = edit(intelligence, files)(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        assertEquals(
            EditResult.Rejected(listOf(Problem(path, "greet", 5, "Type mismatch: inferred type is String", "\"hello\""))),
            result,
        )
        assertEquals(source, files.content(path))
        assertEquals(emptyList(), files.written)
    }

    private fun warning(line: Int, char: Int, message: String) =
        Diagnostic(range(line, char, line, char + 1), Severity.WARNING, message, "WARN")

    @Test
    fun `errors that were there before and warnings never block, and the result reports new warnings and old errors`() = runTest {
        val unresolved = "Unresolved reference: x"
        val intelligence = intelligence(
            diagnosticsByText = mapOf(
                source to listOf(error(6, 23, unresolved), warning(6, 8, "Unused")),
                // Same keys in new places: neither the error nor the old warning is new.
                hello to listOf(error(6, 23, unresolved), warning(6, 8, "Unused"), warning(4, 8, "Redundant string")),
            ),
        )

        val result = edit(intelligence)(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        val written = assertIs<EditResult.Written>(result).files.single()
        assertEquals(listOf(Problem(path, "greet", 5, "Redundant string", "\"hello\"")), written.newWarnings)
        assertEquals(1, written.preexistingErrors)
    }

    @Test
    fun `an error whose code and message were already there is new when their count rises, and found on the edited lines`() = runTest {
        val unresolved = "Unresolved reference: x"
        val intelligence = intelligence(
            diagnosticsByText = mapOf(source to listOf(error(6, 23, unresolved)), hello to listOf(error(4, 8, unresolved), error(6, 23, unresolved))),
        )

        val result = edit(intelligence)(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        assertEquals(EditResult.Rejected(listOf(Problem(path, "greet", 5, unresolved, "\"hello\""))), result)
    }

    @Test
    fun `the Shadow Workspace opens the file as read, changes it, saves it after Write-back and closes it`() = runTest {
        val intelligence = intelligence()

        edit(intelligence)(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        assertEquals(listOf("open A.kt v1", "change A.kt v2", "save A.kt", "close A.kt"), intelligence.events)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a rejected edit closes every document it opened, unsaved`() = runTest {
        val intelligence = intelligence(diagnosticsByText = mapOf(hello to listOf(error(4, 8, "Type mismatch"))))

        edit(intelligence)(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        assertEquals(listOf("open A.kt v1", "change A.kt v2", "close A.kt"), intelligence.events)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `an edit that fails with an exception still closes every document it opened`() = runTest {
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other))),
            beforeDiagnostics = { throw IllegalStateException("language server died") },
        )

        assertFailsWith<IllegalStateException> { edit(intelligence)(Replace(path, "Greeter/greet", "fun greet() = 1")) }

        assertEquals(listOf("open A.kt v1", "close A.kt"), intelligence.events)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a file changed on disk since it was read is not overwritten`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other)), hello to listOf(greeter(helloGreet, other))),
            beforeDiagnostics = { files.writeOutside(path, "$source// theirs\n") },
        )

        val result = edit(intelligence, files)(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        assertEquals(EditResult.ChangedOnDisk(path), result)
        assertEquals("$source// theirs\n", files.content(path))
        assertEquals(listOf("open A.kt v1", "change A.kt v2", "close A.kt"), intelligence.events)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `validation that takes longer than its timeout rejects the edit unwritten`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other))),
            diagnosticsDelay = 10.seconds,
        )

        val result = Edit(files, intelligence, validationTimeout = 15.seconds)(Replace(path, "Greeter/greet", "fun greet() = 1"))

        assertEquals(EditResult.ValidationTimedOut, result)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `waits for readiness before opening anything, and fails unwritten if the index stays incomplete`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = FakeCodeIntelligence(symbolsByText = mapOf(source to listOf(greeter(greet, other))), notReadyPhase = "Indexing")

        val result = Edit(files, intelligence, readinessTimeout = 30.seconds)(Replace(path, "Greeter/greet", "fun greet() = 1"))

        assertEquals(EditResult.NotReady("Indexing", 30.seconds), result)
        assertEquals(emptyList(), intelligence.events)
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `edits sharing a write lock run one at a time`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source, "src/B.kt" to source))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other)), hello to listOf(greeter(helloGreet, other))),
            diagnosticsDelay = 1.seconds,
        )
        val lock = WriteLock()
        val replace = "fun greet(): String =\n    \"hello\""

        listOf(path, "src/B.kt").map { file ->
            async { Edit(files, intelligence, writeLock = lock)(Replace(file, "Greeter/greet", replace)) }
        }.awaitAll()

        assertEquals(
            listOf("A.kt", "B.kt").flatMap { listOf("open $it v1", "change $it v2", "save $it", "close $it") },
            intelligence.events,
        )
    }

    @Test
    fun `an operation whose Symbol Path doesn't resolve rejects the edit with its index and close names`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = intelligence()

        val result = edit(intelligence, files)(Replace(path, "Greeter/greeet", "fun greet() = 1"))

        assertEquals(EditResult.OperationFailed(0, OperationFailure.NotFound("Greeter/greeet", path, listOf(SymbolCandidate("greet", path), SymbolCandidate("Greeter", path)))), result)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `without a file the Symbol Path is resolved across the project`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other)), hello to listOf(greeter(helloGreet, other))),
            index = listOf(SymbolLocation("greet", SymbolKind.FUNCTION, root.resolve(path), greet.range, "Greeter")),
        )

        val result = edit(intelligence, files)(Replace(null, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        assertIs<EditResult.Written>(result)
        assertEquals(hello, files.content(path))
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `the new code may rename the declaration and hold several, each reported with its span`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val twoFunctions = source.replace("    /** Says hi. */\n    fun greet(): String = \"hi\"\n", "    fun hi(): Int = 1\n\n    fun bye(): Int = 2\n")
        val hi = symbol("hi", SymbolKind.FUNCTION, range(3, 4, 3, 21), 3, 8, "(): Int")
        val bye = symbol("bye", SymbolKind.FUNCTION, range(5, 4, 5, 22), 5, 8, "(): Int")
        val movedOther = symbol("other", SymbolKind.FUNCTION, range(7, 4, 7, 24), 7, 8, "(): Int")
        val intelligence = intelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other)), twoFunctions to listOf(greeter(hi, bye, movedOther, lastLine = 8))),
        )

        val result = edit(intelligence, files)(Replace(path, "greet", "fun hi(): Int = 1\n\nfun bye(): Int = 2"))

        assertEquals(twoFunctions, files.content(path))
        assertEquals(listOf(EditedSymbol("hi", 4, 4), EditedSymbol("bye", 6, 6)), assertIs<EditResult.Written>(result).files.single().symbols)
    }

    @Test
    fun `an Operation that fails after others applied reports its own index, and nothing is written`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = intelligence()

        val result = edit(intelligence, files)(
            listOf(
                Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""),
                Replace(path, "Greeter/missing", "fun missing() = 1"),
            ),
        )

        assertIs<OperationFailure.NotFound>(assertIs<EditResult.OperationFailed>(result).failure)
        assertEquals(1, result.index)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    private val renamed = source.replace("    /** Says hi. */\n    fun greet(): String = \"hi\"\n", "    fun hi(): Int = 1\n")
    private val renamedAgain = renamed.replace("fun hi(): Int = 1", "fun hi(): Int = 2")
    private fun renamedSymbols() = listOf(
        greeter(symbol("hi", SymbolKind.FUNCTION, range(3, 4, 3, 21), 3, 8, "(): Int"), symbol("other", SymbolKind.FUNCTION, range(5, 4, 5, 24), 5, 8, "(): Int"), lastLine = 6),
    )
    private fun batchIntelligence(index: List<SymbolLocation> = emptyList()) = FakeCodeIntelligence(
        symbolsByText = mapOf(
            source to listOf(greeter(greet, other)),
            hello to listOf(greeter(helloGreet, other)),
            renamed to renamedSymbols(),
            renamedAgain to renamedSymbols(),
        ),
        index = index,
    )

    @Test
    fun `a batch applies in order across files, a later Operation targets a name an earlier one gave, and each file is reported`() = runTest {
        val other = "src/B.kt"
        val files = FakeFileStore(root, mapOf(path to source, other to source))
        val intelligence = batchIntelligence()

        val result = edit(intelligence, files)(
            listOf(
                Replace(path, "Greeter/greet", "fun hi(): Int = 1"),
                Replace(path, "Greeter/hi", "fun hi(): Int = 2"),
                Replace(other, "Greeter/greet", "fun greet(): String =\n    \"hello\""),
            ),
        )

        assertEquals(
            EditResult.Written(
                listOf(
                    EditedFile(path, listOf(EditedSymbol("hi", 4, 4)), emptyList(), 0, 0),
                    EditedFile(other, listOf(EditedSymbol("greet", 4, 5)), emptyList(), 0, 0),
                ),
            ),
            result,
        )
        assertEquals(renamedAgain, files.content(path))
        assertEquals(hello, files.content(other))
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `an Operation aimed at a Symbol an earlier one removed or renamed says so with close names, and nothing is written`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val greets = source.replace("    /** Says hi. */\n    fun greet(): String = \"hi\"\n", "    fun greets(): Int = 1\n")
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                source to listOf(greeter(greet, other)),
                greets to listOf(greeter(symbol("greets", SymbolKind.FUNCTION, range(3, 4, 3, 25), 3, 8, "(): Int"), symbol("other", SymbolKind.FUNCTION, range(5, 4, 5, 24), 5, 8, "(): Int"), lastLine = 6)),
            ),
        )

        val result = edit(intelligence, files)(
            listOf(Replace(path, "Greeter/greet", "fun greets(): Int = 1"), Replace(path, "Greeter/greet", "fun greet() = 1")),
        )

        assertEquals(EditResult.OperationFailed(1, OperationFailure.RemovedEarlier("Greeter/greet", path, listOf(SymbolCandidate("greets", path)))), result)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `an Operation without a file aimed at a Symbol an earlier one renamed is found on disk, then said to be removed`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = batchIntelligence(index = listOf(SymbolLocation("greet", SymbolKind.FUNCTION, root.resolve(path), greet.range, "Greeter")))

        val result = edit(intelligence, files)(
            listOf(Replace(path, "Greeter/greet", "fun hi(): Int = 1"), Replace(null, "Greeter/greet", "fun greet() = 1")),
        )

        assertIs<OperationFailure.RemovedEarlier>(assertIs<EditResult.OperationFailed>(result).failure)
        assertEquals(1, result.index)
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `an Operation without a file can't target a Symbol an earlier one created, and is told where to find it`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = batchIntelligence()

        val result = edit(intelligence, files)(
            listOf(Replace(path, "Greeter/greet", "fun hi(): Int = 1"), Replace(null, "Greeter/hi", "fun hi(): Int = 2")),
        )

        assertEquals(EditResult.OperationFailed(1, OperationFailure.NeedsFile("Greeter/hi", listOf(SymbolCandidate("hi", path)))), result)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a Write-back that fails part-way reports what was written, and still closes every document`() = runTest {
        val other = "src/B.kt"
        val files = FakeFileStore(root, mapOf(path to source, other to source), failingWrites = setOf(other))
        val intelligence = intelligence()
        val replace = "fun greet(): String =\n    \"hello\""

        val result = edit(intelligence, files)(listOf(Replace(path, "Greeter/greet", replace), Replace(other, "Greeter/greet", replace)))

        assertEquals(EditResult.PartiallyWritten(listOf(path), other, "No space left on device"), result)
        assertEquals(listOf(path to hello), files.written)
        intelligence.assertOneSessionClosedEverything()
    }
}
