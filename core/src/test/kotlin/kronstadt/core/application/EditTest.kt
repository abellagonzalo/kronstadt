package kronstadt.core.application

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
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
        parameterTypes: String? = null, type: String? = null, receiver: String? = null,
        children: List<CodeSymbol> = emptyList(),
    ) = CodeSymbol(name, kind, range, range(nameLine, nameChar, nameLine, nameChar + name.length), children, parameterTypes = parameterTypes, type = type, receiver = receiver)

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
    private val greet = symbol("greet", SymbolKind.FUNCTION, range(3, 4, 4, 30), 4, 8, parameterTypes = "", type = "(): String")
    private val other = symbol("other", SymbolKind.FUNCTION, range(6, 4, 6, 24), 6, 8, parameterTypes = "", type = "(): Int")

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
    private val helloGreet = symbol("greet", SymbolKind.FUNCTION, range(3, 4, 4, 15), 3, 8, parameterTypes = "", type = "(): String")

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

    // A multi-line, trailing-comma constructor: kotlin-lsp's range for each val parameter ends before its `,` (#68).
    private val box = """
        |package a
        |
        |class Box(
        |    val width: Int,
        |    val height: Int, // tall
        |    val depth: Int,
        |)
        |""".trimMargin()
    private val wideBox = box.replace("val width: Int,", "val width: Long,")
    private val flatBox = wideBox.replace("    val height: Int, // tall\n", "")
    private fun boxParameter(name: String, line: Int, type: String) =
        symbol(name, SymbolKind.VARIABLE, range(line, 4, line, 4 + "val $name: $type".length), line, 8, type = ": $type")
    private fun boxClass(lastLine: Int, vararg parameters: CodeSymbol) =
        symbol("Box", SymbolKind.CLASS, range(2, 0, lastLine, 1), 2, 6, children = parameters.toList())

    @Test
    fun `in a trailing-comma constructor, a val parameter is replaced with its comma and deleted with it`() = runTest {
        val files = FakeFileStore(root, mapOf(path to box))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                box to listOf(boxClass(6, boxParameter("width", 3, "Int"), boxParameter("height", 4, "Int"), boxParameter("depth", 5, "Int"))),
                wideBox to listOf(boxClass(6, boxParameter("width", 3, "Long"), boxParameter("height", 4, "Int"), boxParameter("depth", 5, "Int"))),
                flatBox to listOf(boxClass(5, boxParameter("width", 3, "Long"), boxParameter("depth", 4, "Int"))),
            ),
        )

        val result = edit(intelligence, files)(listOf(Replace(path, "Box/width", "val width: Long,"), Delete(path, "Box/height")))

        assertIs<EditResult.Written>(result)
        assertEquals("package a\n\nclass Box(\n    val width: Long,\n    val depth: Int,\n)\n", files.content(path))
    }

    @Test
    fun `in a trailing-comma constructor, edit_in on a val parameter matches text that takes in its comma`() = runTest {
        val tallBox = box.replace("val height: Int, // tall", "val height: Long, // tall")
        val files = FakeFileStore(root, mapOf(path to box))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                box to listOf(boxClass(6, boxParameter("width", 3, "Int"), boxParameter("height", 4, "Int"), boxParameter("depth", 5, "Int"))),
                tallBox to listOf(boxClass(6, boxParameter("width", 3, "Int"), boxParameter("height", 4, "Long"), boxParameter("depth", 5, "Int"))),
            ),
        )

        val result = edit(intelligence, files)(EditIn(path, "Box/height", old = "Int, // tall", new = "Long, // tall"))

        assertIs<EditResult.Written>(result)
        assertEquals(tallBox, files.content(path))
    }

    @Test
    fun `a replace that introduces an error is rejected with where it is and what it says, and nothing is written`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = intelligence(diagnosticsByText = mapOf(hello to listOf(error(4, 8, "Type mismatch: inferred type is String"))))

        val result = edit(intelligence, files)(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        assertEquals(
            EditResult.Rejected(listOf(Problem(path, "greet", 5, "Type mismatch: inferred type is String", "\"hello\"")), "e1"),
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

        assertEquals(EditResult.Rejected(listOf(Problem(path, "greet", 5, unresolved, "\"hello\"")), "e1"), result)
    }

    /** What kotlin-lsp reports for a declaration without visibility or return type, as explicit-API mode wants (#48). */
    private fun explicitApi(line: Int, char: Int) = listOf(
        error(line, char, "Visibility must be specified in explicit API mode.", "NO_EXPLICIT_VISIBILITY_IN_API_MODE"),
        error(line, char, "Return type must be specified in explicit API mode.", "NO_EXPLICIT_RETURN_TYPE_IN_API_MODE"),
    )

    /** Explicit-API errors on `other` before the Edit, and on `greet` too after it. */
    private val explicitApiEverywhere = mapOf(source to explicitApi(6, 4), hello to explicitApi(3, 4) + explicitApi(6, 4))

    @Test
    fun `in a test source set, explicit-API errors neither block an edit nor count as already there, since the build never reports them`() = runTest {
        for (testPath in listOf("src/test/kotlin/a/A.kt", "src/jvmTest/kotlin/a/A.kt", "src/integrationTest/kotlin/a/A.kt")) {
            val files = FakeFileStore(root, mapOf(testPath to source))

            val result = edit(intelligence(diagnosticsByText = explicitApiEverywhere), files)(
                Replace(testPath, "Greeter/greet", "fun greet(): String =\n    \"hello\""),
            )

            assertEquals(hello, files.content(testPath), testPath)
            assertEquals(0, assertIs<EditResult.Written>(result, testPath).files.single().preexistingErrors, testPath)
        }
    }

    @Test
    fun `in a main source set, an explicit-API error the edit adds still rejects it`() = runTest {
        for (mainPath in listOf("src/main/kotlin/a/A.kt", "src/jvmMain/kotlin/a/A.kt", "src/A.kt")) {
            val files = FakeFileStore(root, mapOf(mainPath to source))

            val result = edit(intelligence(diagnosticsByText = explicitApiEverywhere), files)(
                Replace(mainPath, "Greeter/greet", "fun greet(): String =\n    \"hello\""),
            )

            assertEquals(2, assertIs<EditResult.Rejected>(result, mainPath).newErrors.size, mainPath)
            assertEquals(source, files.content(mainPath), mainPath)
        }
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

        assertEquals(EditResult.NotReady(Readiness.NotReady("Indexing", 30.seconds)), result)
        assertEquals(emptyList(), intelligence.events)
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `an invalid Symbol Path in any Operation fails before waiting for readiness`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = FakeCodeIntelligence(notReadyPhase = "Indexing")

        val result = Edit(files, intelligence, readinessTimeout = 30.seconds)(
            listOf(Replace(path, "Greeter/greet", "fun greet() = 1"), Delete(path, "com.example/greet")),
        )

        assertEquals(1, assertIs<EditResult.OperationFailed>(result).index)
        assertIs<LookupFailure.InvalidPath>(result.lookupFailure())
        assertEquals(0, intelligence.readinessAwaits)
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

        assertEquals(EditResult.OperationFailed(0, OperationFailure.LookupFailed(LookupFailure.NotFound("Greeter/greeet", path, listOf(SymbolCandidate("greet", path), SymbolCandidate("Greeter", path))))), result)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `without a file the Symbol Path is resolved across the project, after waiting for readiness once`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other)), hello to listOf(greeter(helloGreet, other))),
            index = listOf(SymbolLocation("greet", SymbolKind.FUNCTION, root.resolve(path), greet.range, "Greeter")),
        )

        val result = edit(intelligence, files)(Replace(null, "Greeter/greet", "fun greet(): String =\n    \"hello\""))

        assertIs<EditResult.Written>(result)
        assertEquals(hello, files.content(path))
        assertEquals(1, intelligence.readinessAwaits)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `the new code may rename the declaration and hold several, each reported with its span`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val twoFunctions = source.replace("    /** Says hi. */\n    fun greet(): String = \"hi\"\n", "    fun hi(): Int = 1\n\n    fun bye(): Int = 2\n")
        val hi = symbol("hi", SymbolKind.FUNCTION, range(3, 4, 3, 21), 3, 8, parameterTypes = "", type = "(): Int")
        val bye = symbol("bye", SymbolKind.FUNCTION, range(5, 4, 5, 22), 5, 8, parameterTypes = "", type = "(): Int")
        val movedOther = symbol("other", SymbolKind.FUNCTION, range(7, 4, 7, 24), 7, 8, parameterTypes = "", type = "(): Int")
        val intelligence = intelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other)), twoFunctions to listOf(greeter(hi, bye, movedOther, lastLine = 8))),
        )

        val result = edit(intelligence, files)(Replace(path, "greet", "fun hi(): Int = 1\n\nfun bye(): Int = 2"))

        assertEquals(twoFunctions, files.content(path))
        assertEquals(listOf(EditedSymbol("hi", 4, 4), EditedSymbol("bye", 6, 6)), assertIs<EditResult.Written>(result).files.single().symbols)
    }

    @Test
    fun `a replace whose code holds a multi-line raw string writes the string's lines exactly as sent`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val code = "fun greet(): String =\n    \"\"\"\n  Hello,\n    world\n\"\"\"\n"
        val written = source.replace(
            "    /** Says hi. */\n    fun greet(): String = \"hi\"\n",
            "    fun greet(): String =\n        \"\"\"\n  Hello,\n    world\n\"\"\"\n",
        )
        val newGreet = symbol("greet", SymbolKind.FUNCTION, range(3, 4, 7, 3), 3, 8, parameterTypes = "", type = "(): String")
        val movedOther = symbol("other", SymbolKind.FUNCTION, range(9, 4, 9, 24), 9, 8, parameterTypes = "", type = "(): Int")
        val intelligence = intelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other)), written to listOf(greeter(newGreet, movedOther, lastLine = 10))),
        )

        val result = edit(intelligence, files)(Replace(path, "Greeter/greet", code))

        assertEquals(written, files.content(path))
        assertEquals(listOf(EditedSymbol("greet", 4, 8)), assertIs<EditResult.Written>(result).files.single().symbols)
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

        assertIs<LookupFailure.NotFound>(result.lookupFailure())
        assertEquals(1, assertIs<EditResult.OperationFailed>(result).index)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    private val renamed = source.replace("    /** Says hi. */\n    fun greet(): String = \"hi\"\n", "    fun hi(): Int = 1\n")
    private val renamedAgain = renamed.replace("fun hi(): Int = 1", "fun hi(): Int = 2")
    private fun renamedSymbols() = listOf(
        greeter(symbol("hi", SymbolKind.FUNCTION, range(3, 4, 3, 21), 3, 8, parameterTypes = "", type = "(): Int"), symbol("other", SymbolKind.FUNCTION, range(5, 4, 5, 24), 5, 8, parameterTypes = "", type = "(): Int"), lastLine = 6),
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
                greets to listOf(greeter(symbol("greets", SymbolKind.FUNCTION, range(3, 4, 3, 25), 3, 8, parameterTypes = "", type = "(): Int"), symbol("other", SymbolKind.FUNCTION, range(5, 4, 5, 24), 5, 8, parameterTypes = "", type = "(): Int"), lastLine = 6)),
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

    private fun hiAt(file: String) = SymbolLocation("hi", SymbolKind.FUNCTION, root.resolve(file), range(3, 4, 3, 21), "Greeter")

    @Test
    fun `an Operation without a file whose Symbol is on disk in an unedited file and was made earlier in another is ambiguous between both`() = runTest {
        val b = "src/B.kt"
        val files = FakeFileStore(root, mapOf(path to source, b to renamed))
        val intelligence = batchIntelligence(index = listOf(hiAt(b)))

        val result = edit(intelligence, files)(
            listOf(Replace(path, "Greeter/greet", "fun hi(): Int = 1"), Replace(null, "Greeter/hi", "fun hi(): Int = 2")),
        )

        assertEquals(
            EditResult.OperationFailed(1, OperationFailure.LookupFailed(LookupFailure.Ambiguous("Greeter/hi", null, listOf(SymbolCandidate("hi", b), SymbolCandidate("hi", path))))),
            result,
        )
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `an Operation without a file whose Symbol is in a file an earlier one edited resolves against that file's current text`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = batchIntelligence(index = listOf(SymbolLocation("greet", SymbolKind.FUNCTION, root.resolve(path), greet.range, "Greeter")))

        val result = edit(intelligence, files)(
            listOf(
                Replace(path, "Greeter/other", "fun other(): Int = 1"),
                Replace(null, "Greeter/greet", "fun greet(): String =\n    \"hello\""),
            ),
        )

        assertIs<EditResult.Written>(result)
        assertEquals(hello, files.content(path))
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `an Operation without a file whose Symbol an earlier one renamed away on disk and made in another file needs that file`() = runTest {
        val b = "src/B.kt"
        val files = FakeFileStore(root, mapOf(path to source, b to renamed))
        val intelligence = batchIntelligence(index = listOf(hiAt(b)))

        val result = edit(intelligence, files)(
            listOf(
                Replace(b, "Greeter/hi", "/** Says hi. */\nfun greet(): String = \"hi\""),
                Replace(path, "Greeter/greet", "fun hi(): Int = 1"),
                Replace(null, "Greeter/hi", "fun hi(): Int = 2"),
            ),
        )

        assertEquals(EditResult.OperationFailed(2, OperationFailure.NeedsFile("Greeter/hi", listOf(SymbolCandidate("hi", path)))), result)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `an Operation without a file ambiguous on disk also lists the Symbol an earlier one made`() = runTest {
        val b = "src/B.kt"
        val c = "src/C.kt"
        val files = FakeFileStore(root, mapOf(path to source, b to renamed, c to renamed))
        val intelligence = batchIntelligence(index = listOf(hiAt(b), hiAt(c)))

        val result = edit(intelligence, files)(
            listOf(Replace(path, "Greeter/greet", "fun hi(): Int = 1"), Replace(null, "Greeter/hi", "fun hi(): Int = 2")),
        )

        assertEquals(
            EditResult.OperationFailed(
                1,
                OperationFailure.LookupFailed(
                    LookupFailure.Ambiguous("Greeter/hi", null, listOf(SymbolCandidate("hi", b), SymbolCandidate("hi", c), SymbolCandidate("hi", path))),
                ),
            ),
            result,
        )
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

    private val created = "src/b/B.kt"
    private val hello1 = """
        |package a
        |
        |class Hello {
        |    fun hi(): Int = 1
        |}
        |""".trimMargin()
    private val hello2 = hello1.replace("= 1", "= 2")
    private fun helloSymbols() = listOf(
        symbol("Hello", SymbolKind.CLASS, range(2, 0, 4, 1), 2, 6, children = listOf(symbol("hi", SymbolKind.FUNCTION, range(3, 4, 3, 21), 3, 8, parameterTypes = "", type = "(): Int"))),
    )
    private fun createIntelligence(diagnosticsByText: Map<String, List<Diagnostic>> = emptyMap(), beforeDiagnostics: suspend () -> Unit = {}) =
        FakeCodeIntelligence(
            symbolsByText = mapOf(source to listOf(greeter(greet, other)), hello1 to helloSymbols(), hello2 to helloSymbols()),
            diagnosticsByText = diagnosticsByText,
            beforeDiagnostics = beforeDiagnostics,
        )

    @Test
    fun `a create makes a new file with its line breaks as newlines, a later Operation targets its Symbols, and it is written as a whole`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = createIntelligence()

        val result = edit(intelligence, files)(
            listOf(Create(created, hello1.replace("\n", "\r\n")), Replace(created, "Hello/hi", "fun hi(): Int = 2")),
        )

        assertEquals(EditResult.Written(listOf(EditedFile(created, listOf(EditedSymbol("Hello", 3, 5)), emptyList(), 0, 0, created = true))), result)
        assertEquals(listOf(created to hello2), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a create and an edit of an existing file that uses the new class are one Edit, validated and written back together`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val using = source.replace("fun other(): Int = 1", "fun other(): Int = Hello().hi()")
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                source to listOf(greeter(greet, other)),
                using to listOf(greeter(greet, symbol("other", SymbolKind.FUNCTION, range(6, 4, 6, 35), 6, 8, parameterTypes = "", type = "(): Int"))),
                hello1 to helloSymbols(),
            ),
        )

        val result = edit(intelligence, files)(
            listOf(Create(created, hello1), EditIn(path, null, "fun other(): Int = 1", "fun other(): Int = Hello().hi()")),
        )

        val written = assertIs<EditResult.Written>(result, "$result").files
        assertEquals(listOf(created, path), written.map { it.file })
        assertEquals(listOf(true, false), written.map { it.created })
        assertEquals(listOf(created, path), files.written.map { it.first })
        assertEquals(hello1, files.content(created))
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a created file with a compile error rejects the whole Edit, and nothing is written or created`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = createIntelligence(diagnosticsByText = mapOf(hello1 to listOf(error(3, 20, "Return type mismatch"))))

        val result = edit(intelligence, files)(
            listOf(Create(created, hello1), Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\"")),
        )

        assertEquals(EditResult.Rejected(listOf(Problem(created, "hi", 4, "Return type mismatch", "fun hi(): Int = 1")), "e1"), result)
        assertEquals(emptyList(), files.written)
        assertEquals(null, files.content(created))
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a created file counts no errors as already there, even ones its empty base would have`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = createIntelligence(diagnosticsByText = mapOf("" to listOf(error(0, 0, "Return type mismatch")), hello1 to listOf(error(0, 0, "Return type mismatch"))))

        val result = edit(intelligence, files)(Create(created, hello1))

        assertIs<EditResult.Rejected>(result, "$result")
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `a create fails, naming the Operation, for a file that exists, is outside the project, isn't Kotlin or was created earlier`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        suspend fun failure(vararg operations: Operation) = edit(createIntelligence(), files)(operations.toList())

        assertEquals(EditResult.OperationFailed(0, OperationFailure.FileExists(path, inEdit = false)), failure(Create(path, hello1)))
        assertEquals(LookupFailure.OutsideProject("../B.kt"), failure(Create("../B.kt", hello1)).lookupFailure())
        assertEquals(LookupFailure.NotKotlin("src/b/B.java"), failure(Create("src/b/B.java", hello1)).lookupFailure())
        assertEquals(
            EditResult.OperationFailed(1, OperationFailure.FileExists(created, inEdit = true)),
            failure(Create(created, hello1), Create(created, hello2)),
        )
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `a file that appears on disk where the Edit creates one is not overwritten, and nothing is written`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = createIntelligence(beforeDiagnostics = { files.writeOutside(created, "// theirs\n") })

        val result = edit(intelligence, files)(
            listOf(Replace(path, "Greeter/greet", "fun greet(): String =\n    \"hello\""), Create(created, hello1)),
        )

        assertEquals(EditResult.ChangedOnDisk(created), result)
        assertEquals("// theirs\n", files.content(created))
        assertEquals(source, files.content(path))
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a create's imports go after its package line, or at the top of a file without one`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))
        val imports = listOf("kotlin.time.Duration")

        edit(createIntelligence(), files)(Create(created, "package a\n\nclass Wait(val d: Duration)\n", imports))
        edit(createIntelligence(), files)(Create("src/C.kt", "class Wait(val d: Duration)\n", imports))

        assertEquals("package a\n\nimport kotlin.time.Duration\n\nclass Wait(val d: Duration)\n", files.content(created))
        assertEquals("import kotlin.time.Duration\n\nclass Wait(val d: Duration)\n", files.content("src/C.kt"))
    }

    @Test
    fun `in a file without a package line, a create's imports go below a leading licence comment`() = runTest {
        val files = FakeFileStore(root, mapOf(path to source))

        edit(createIntelligence(), files)(
            Create("src/C.kt", "/* Licence */\n\nclass Wait(val d: Duration)\n", listOf("kotlin.time.Duration")),
        )

        assertEquals(
            "/* Licence */\n\nimport kotlin.time.Duration\n\nclass Wait(val d: Duration)\n",
            files.content("src/C.kt"),
        )
    }
}
