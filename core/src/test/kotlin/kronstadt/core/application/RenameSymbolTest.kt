package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.FileChange
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolCandidate
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.range
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

class RenameSymbolTest {
    private val root = Path.of("/project")
    private val greeterPath = "src/a/Greeting.kt"
    private val mainPath = "src/b/Main.kt"
    private val greeterFile = root.resolve(greeterPath)
    private val mainFile = root.resolve(mainPath)

    private fun name(line: Int, char: Int, length: Int) = range(line, char, line, char + length)

    private val greeting = """
        |package a
        |
        |class Greeter {
        |    fun greet(): String = "hi"
        |}
        |""".trimMargin()
    private val main = """
        |package b
        |
        |import a.Greeter
        |
        |fun main() {
        |    println(Greeter().greet())
        |    Greeter().greet()
        |}
        |""".trimMargin()

    private fun greeter(member: String) = listOf(
        CodeSymbol(
            "Greeter", SymbolKind.CLASS, range(2, 0, 4, 1), name(2, 6, 7),
            listOf(CodeSymbol(member, SymbolKind.FUNCTION, range(3, 4, 3, 30 + member.length - 5), name(3, 8, member.length), parameterTypes = "", type = "(): String")),
        ),
    )

    /** The code intelligence's answer for renaming `greet`: the declaration and both calls, in a file it never opened. */
    private val greetRenamed = Rename.Changes(
        listOf(
            FileChange.Edited(mainFile, listOf(name(5, 22, 5), name(6, 14, 5))),
            FileChange.Edited(greeterFile, listOf(name(3, 8, 5))),
        ),
    )

    private fun files() = FakeFileStore(root, mapOf(greeterPath to greeting, mainPath to main))

    private fun intelligence() = FakeCodeIntelligence(
        symbolsByText = mapOf(greeting to greeter("greet"), greeting.replace("greet()", "hello()") to greeter("hello")),
        index = listOf(SymbolLocation("greet", SymbolKind.FUNCTION, greeterFile, name(3, 8, 5))),
        renames = mapOf((greeterFile to Position(3, 8)) to greetRenamed),
    )

    @Test
    fun `renames a Symbol and every reference across files, and reports the files, their Usages and imports, and the new path`() = runTest {
        val files = files()
        val intelligence = intelligence()

        val result = RenameSymbol(files, intelligence)("Greeter/greet", "hello")

        assertEquals(greeting.replace("greet()", "hello()"), files.content(greeterPath))
        assertEquals(main.replace("greet()", "hello()"), files.content(mainPath))
        assertEquals(
            RenameResult.Renamed(
                newPath = "hello",
                files = listOf(RenamedFile(greeterPath, usages = 0), RenamedFile(mainPath, usages = 2)),
            ),
            result,
        )
        // Resolved without a file, yet the index is waited for only once, up front.
        assertEquals(1, intelligence.readinessAwaits)
        intelligence.assertOneSessionClosedEverything()
    }

    private val classPath = "src/a/Greeter.kt"
    private val classFile = root.resolve(classPath)

    /** Renaming `Greeter` to `Host`: its import and two Usages in [main], its declaration, and its file. */
    private val classRenamed = Rename.Changes(
        listOf(
            FileChange.Edited(mainFile, listOf(name(2, 9, 7), name(5, 12, 7), name(6, 4, 7))),
            FileChange.Edited(classFile, listOf(name(2, 6, 7))),
            FileChange.Moved(classFile, root.resolve("src/a/Host.kt")),
        ),
    )

    private fun classIntelligence() = FakeCodeIntelligence(
        symbolsByText = mapOf(greeting to greeter("greet"), greeting.replace("Greeter", "Host") to host()),
        renames = mapOf((classFile to Position(2, 6)) to classRenamed),
    )

    private fun host() = listOf(
        CodeSymbol(
            "Host", SymbolKind.CLASS, range(2, 0, 4, 1), name(2, 6, 4),
            listOf(CodeSymbol("greet", SymbolKind.FUNCTION, range(3, 4, 3, 30), name(3, 8, 5), parameterTypes = "", type = "(): String")),
        ),
    )

    @Test
    fun `renaming a class named like its file renames the file in the same Write-back`() = runTest {
        val files = FakeFileStore(root, mapOf(classPath to greeting, mainPath to main))
        val intelligence = classIntelligence()

        val result = RenameSymbol(files, intelligence)("Greeter", "Host", file = classPath)

        assertEquals(null, files.content(classPath))
        assertEquals(greeting.replace("Greeter", "Host"), files.content("src/a/Host.kt"))
        assertEquals(main.replace("Greeter", "Host"), files.content(mainPath))
        assertEquals(
            RenameResult.Renamed(
                newPath = "Host",
                files = listOf(RenamedFile(classPath, usages = 0), RenamedFile(mainPath, usages = 2, imports = 1)),
                fileRenamed = FileRenamed(classPath, "src/a/Host.kt"),
            ),
            result,
        )
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a file rename onto an existing file is refused, and nothing is written`() = runTest {
        val files = FakeFileStore(root, mapOf(classPath to greeting, mainPath to main, "src/a/Host.kt" to "package a\n"))

        val result = RenameSymbol(files, classIntelligence())("Greeter", "Host", file = classPath)

        assertEquals(RenameResult.WriteBackFailed(WriteBackFailure.TargetExists(classPath, "src/a/Host.kt")), result)
        assertEquals(emptyList(), files.written)
        assertEquals(greeting, files.content(classPath))
    }

    private val withUnused = main.replace("import a.Greeter\n", "import a.Greeter\nimport a.Unused\n")

    /** Renames `Greeter` to `Host` in [withUnused], whose `import a.Unused` the code intelligence finds unused in the texts [unusedByText] gives. */
    private suspend fun renameWithUnused(unusedByText: Map<String, Set<String>>): Triple<FakeFileStore, FakeCodeIntelligence, RenameResult> {
        val files = FakeFileStore(root, mapOf(classPath to greeting, mainPath to withUnused))
        val renamed = Rename.Changes(
            listOf(
                FileChange.Edited(mainFile, listOf(name(2, 9, 7), name(6, 12, 7), name(7, 4, 7))),
                FileChange.Edited(classFile, listOf(name(2, 6, 7))),
            ),
        )
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(greeting to greeter("greet"), greeting.replace("Greeter", "Host") to host()),
            renames = mapOf((classFile to Position(2, 6)) to renamed),
            unusedByText = unusedByText,
        )
        return Triple(files, intelligence, RenameSymbol(files, intelligence)("Greeter", "Host", file = classPath))
    }

    @Test
    fun `an import the rename made unused goes and is reported, and every import the file had is asked about`() = runTest {
        // a.Unused is used before the rename and unused after it.
        val (files, intelligence, result) = renameWithUnused(mapOf(withUnused.replace("Greeter", "Host") to setOf("a.Unused")))

        assertEquals(main.replace("Greeter", "Host"), files.content(mainPath))
        assertEquals(
            RenameResult.Renamed(
                newPath = "Host",
                files = listOf(RenamedFile(classPath, usages = 0), RenamedFile(mainPath, usages = 2, imports = 1)),
                importsRemoved = listOf(RemovedImport(mainPath, "a.Unused")),
            ),
            result,
        )
        assertEquals(setOf("a.Greeter", "a.Unused"), intelligence.unusedAsked.first { it.first == mainFile }.second)
    }

    @Test
    fun `an import that was already unused stays, like kt_edit leaves it`() = runTest {
        // a.Unused is unused before the rename too.
        val (files, _, result) = renameWithUnused(
            mapOf(withUnused to setOf("a.Unused"), withUnused.replace("Greeter", "Host") to setOf("a.Unused")),
        )

        assertEquals(withUnused.replace("Greeter", "Host"), files.content(mainPath))
        assertEquals(emptyList(), assertIs<RenameResult.Renamed>(result).importsRemoved)
    }

    @Test
    fun `every import stays when the code intelligence finds no Unused Imports`() = runTest {
        val (files, _, result) = renameWithUnused(emptyMap())

        assertEquals(withUnused.replace("Greeter", "Host"), files.content(mainPath))
        assertEquals(emptyList(), assertIs<RenameResult.Renamed>(result).importsRemoved)
    }

    @Test
    fun `prepare-rename is asked first, and its refusal is passed through with nothing written`() = runTest {
        val files = files()
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(greeting to greeter("greet")),
            renames = mapOf((greeterFile to Position(3, 8)) to greetRenamed),
            renameRefusals = mapOf((greeterFile to Position(3, 8)) to "Cannot rename a library declaration"),
        )

        val result = RenameSymbol(files, intelligence)("Greeter/greet", "hello", file = greeterPath)

        assertEquals(RenameResult.CannotRename("greet", "Cannot rename a library declaration"), result)
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a constructor can't be renamed, only its class`() = runTest {
        val source = "package a\n\nclass Box(val size: Int) {\n    constructor() : this(0)\n}\n"
        val constructor = CodeSymbol("constructor", SymbolKind.CONSTRUCTOR, range(3, 4, 3, 27), range(3, 4, 3, 27), parameterTypes = "", type = "()")
        val box = CodeSymbol("Box", SymbolKind.CLASS, range(2, 0, 4, 1), name(2, 6, 3), listOf(constructor))
        val files = FakeFileStore(root, mapOf("src/a/Box.kt" to source))

        val result = RenameSymbol(files, FakeCodeIntelligence(symbolsByText = mapOf(source to listOf(box))))(
            "Box/constructor()", "make", file = "src/a/Box.kt",
        )

        assertEquals(RenameResult.CannotRename("constructor", "a constructor has no name of its own; rename its class"), result)
    }

    @Test
    fun `a new name that isn't a Kotlin identifier is refused before anything is asked`() = runTest {
        val intelligence = intelligence()

        listOf("", "two words", "1st", "a.b", "`a`b`").forEach { newName ->
            assertEquals(RenameResult.InvalidName(newName), RenameSymbol(files(), intelligence)("Greeter/greet", newName), newName)
        }
        assertEquals(emptyList(), intelligence.sessions)
    }

    @Test
    fun `a rename that would change a file outside the project is refused, writing nothing`() = runTest {
        val files = files()
        val renamed = Rename.Changes(greetRenamed.changes + FileChange.Edited(Path.of("/elsewhere/Lib.kt"), listOf(name(0, 0, 5))))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(greeting to greeter("greet")),
            renames = mapOf((greeterFile to Position(3, 8)) to renamed),
        )

        val result = RenameSymbol(files, intelligence)("Greeter/greet", "hello", file = greeterPath)

        assertEquals(RenameResult.CannotRename("greet", "it would change /elsewhere/Lib.kt, outside the project"), result)
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `a rename that introduces an error in any changed file is rejected, and nothing is written`() = runTest {
        val files = files()
        val renamedMain = main.replace("greet()", "hello()")
        val clash = Diagnostic(name(6, 14, 5), Severity.ERROR, "Overload resolution ambiguity", "AMBIGUITY")
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(greeting to greeter("greet"), greeting.replace("greet()", "hello()") to greeter("hello")),
            diagnosticsByText = mapOf(renamedMain to listOf(clash)),
            renames = mapOf((greeterFile to Position(3, 8)) to greetRenamed),
        )

        val result = RenameSymbol(files, intelligence)("Greeter/greet", "hello", file = greeterPath)

        assertEquals(
            RenameResult.ValidationFailed(ValidationFailure.Rejected(listOf(Problem(mainPath, null, 7, "Overload resolution ambiguity", "Greeter().hello()")))),
            result,
        )
        assertEquals(emptyList(), files.written)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `waits for readiness, and fails with the phase when it doesn't come, writing nothing`() = runTest {
        val files = files()

        val result = RenameSymbol(files, FakeCodeIntelligence(notReadyPhase = "Indexing"), readinessTimeout = 5.seconds)(
            "Greeter/greet", "hello", file = greeterPath,
        )

        assertEquals(RenameResult.NotReady(Readiness.NotReady("Indexing", 5.seconds)), result)
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `a Symbol Path that matches nothing fails like a kt_edit Operation's`() = runTest {
        val result = RenameSymbol(files(), intelligence())("Greeter/gret", "hello", file = greeterPath)

        val failure = assertIs<LookupFailure.NotFound>(result.lookupFailure())
        assertEquals(listOf(SymbolCandidate("Greeter/greet", greeterPath)), failure.suggestions)
    }

    @Test
    fun `an import line with several occurrences counts that import once, and backticks in the new name are written as given`() = runTest {
        val aliased = main.replace("import a.Greeter\n", "import a.Greeter as Greeter\n")
        val files = FakeFileStore(root, mapOf(classPath to greeting, mainPath to aliased))
        val renamed = Rename.Changes(
            listOf(
                FileChange.Edited(mainFile, listOf(name(2, 9, 7), name(2, 20, 7), name(5, 12, 7), name(6, 4, 7))),
                FileChange.Edited(classFile, listOf(name(2, 6, 7))),
            ),
        )
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(greeting to greeter("greet")),
            renames = mapOf((classFile to Position(2, 6)) to renamed),
        )

        val result = RenameSymbol(files, intelligence)("Greeter", "`Great one`", file = classPath)

        assertEquals(aliased.replace("Greeter", "`Great one`"), files.content(mainPath))
        assertEquals(
            listOf(RenamedFile(classPath, usages = 0), RenamedFile(mainPath, usages = 2, imports = 1)),
            assertIs<RenameResult.Renamed>(result, "$result").files,
        )
    }

    @Test
    fun `an error already there whose message names the Symbol isn't new because the rename changed the name in it`() = runTest {
        val files = FakeFileStore(root, mapOf(classPath to greeting, mainPath to main))
        fun unresolved(type: String) = Diagnostic(name(6, 14, 5), Severity.ERROR, "Unresolved reference 'x' on receiver of type '$type'.", "UNRESOLVED_REFERENCE")
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(greeting to greeter("greet"), greeting.replace("Greeter", "Host") to host()),
            diagnosticsByText = mapOf(main to listOf(unresolved("Greeter")), main.replace("Greeter", "Host") to listOf(unresolved("Host"))),
            renames = mapOf((classFile to Position(2, 6)) to classRenamed),
        )

        val result = RenameSymbol(files, intelligence)("Greeter", "Host", file = classPath)

        val renamed = assertIs<RenameResult.Renamed>(result, "$result")
        assertEquals(
            listOf(Problem(mainPath, null, 7, "Unresolved reference 'x' on receiver of type 'Host'.", "Host().greet()")),
            renamed.files.single { it.file == mainPath }.preexistingErrors,
        )
    }
}
