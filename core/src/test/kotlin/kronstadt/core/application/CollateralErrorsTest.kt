package kronstadt.core.application

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Collateral Errors: after a successful Edit, the errors it introduced in files it didn't touch, such as a caller still
 * using a signature it changed. They are reported with the result and never block the Edit.
 */
class CollateralErrorsTest {
    private val root = Path.of("/project")
    private val a = "src/A.kt"
    private val b = "src/B.kt"

    private fun range(line: Int, start: Int, end: Int) = TextRange(Position(line, start), Position(line, end))

    /** A top-level function `fun <name>…` on [line] of [text], its name at column 4. */
    private fun function(text: String, name: String, line: Int, type: String) = CodeSymbol(
        name,
        SymbolKind.FUNCTION,
        TextRange(Position(line, 0), Position(line, text.lines()[line].length)),
        range(line, 4, 4 + name.length),
        parameterTypes = "",
        type = type,
    )

    private fun error(line: Int, message: String) = Diagnostic(range(line, 17, 18), Severity.ERROR, message, "ERR")

    private val answer = "package a\n\nfun answer(): Int = 1\n"
    private val answerString = "package a\n\nfun answer(): String = \"1\"\n"
    private val answerTwo = "package a\n\nfun answer(): Int = 2\n"
    private val caller = "package b\n\nimport a.answer\n\nfun use(): Int = answer() + 1\n"
    private val mismatch = "Return type mismatch: expected 'Int', actual 'String'."

    private val reply = "package a\n\nfun reply(): Int = 1\n"
    private val unresolved = "Unresolved reference 'answer'."

    private fun intelligence(
        callerErrorsAtBase: List<Diagnostic> = emptyList(),
        references: List<SourceLocation> = listOf(SourceLocation(root.resolve(b), range(4, 17, 23))),
        newErrors: List<Diagnostic> = listOf(error(4, mismatch)),
        failingDiagnostics: Set<String> = emptySet(),
        beforeDiagnostics: suspend () -> Unit = {},
    ) = FakeCodeIntelligence(
        symbolsByText = mapOf(
            answer to listOf(function(answer, "answer", 2, "(): Int")),
            answerString to listOf(function(answerString, "answer", 2, "(): String")),
            answerTwo to listOf(function(answerTwo, "answer", 2, "(): Int")),
            caller to listOf(function(caller, "use", 4, "(): Int")),
            reply to listOf(function(reply, "reply", 2, "(): Int")),
        ),
        failingDiagnostics = failingDiagnostics,
        beforeDiagnostics = beforeDiagnostics,
        references = mapOf((root.resolve(a) to Position(2, 4)) to references),
        // Every file but A uses `answer`, and breaks once it returns a String or is renamed.
        diagnosticsInContext = { file, _, open ->
            when {
                file == root.resolve(a) -> null
                open[root.resolve(a)] == answerString -> callerErrorsAtBase + newErrors
                open[root.resolve(a)] == reply -> callerErrorsAtBase + error(4, unresolved)
                else -> callerErrorsAtBase
            }
        },
    )

    private fun files() = FakeFileStore(root, mapOf(a to answer, b to caller))

    @Test
    fun `changing a function's return type reports the caller it broke in an untouched file, and writes the Edit`() = runTest {
        val files = files()
        val intelligence = intelligence()

        val result = Edit(files, intelligence)(Replace(a, "answer", "fun answer(): String = \"1\""))

        val written = assertIs<EditResult.Written>(result)
        assertEquals(
            CollateralErrors(listOf(Problem(b, "use", 5, mismatch, "fun use(): Int = answer() + 1"))),
            written.collateral,
        )
        assertEquals(answerString, files.content(a))
        assertEquals(caller, files.content(b))
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `an Edit that only changes a body pulls no Usages and no diagnostics of untouched files`() = runTest {
        val intelligence = intelligence()

        val result = Edit(files(), intelligence)(Replace(a, "answer", "fun answer(): Int = 2"))

        assertEquals(CollateralErrors(), assertIs<EditResult.Written>(result).collateral)
        assertEquals(emptyList(), intelligence.referenced)
        assertEquals(listOf(root.resolve(a)), intelligence.diagnosed.distinct())
    }

    @Test
    fun `an error the untouched file already had isn't reported`() = runTest {
        val intelligence = intelligence(callerErrorsAtBase = listOf(error(2, "Unresolved reference 'gone'.")))

        val result = Edit(files(), intelligence)(Replace(a, "answer", "fun answer(): String = \"1\""))

        assertEquals(listOf(mismatch), assertIs<EditResult.Written>(result).collateral.errors.map { it.message })
    }

    @Test
    fun `renaming a Symbol reports the Usages it left unresolved`() = runTest {
        val result = Edit(files(), intelligence())(Replace(a, "answer", "fun reply(): Int = 1"))

        assertEquals(listOf(b to unresolved), assertIs<EditResult.Written>(result).collateral.errors.map { it.file to it.message })
    }

    @Test
    fun `removing a Symbol reports the Usages it left unresolved`() = runTest {
        val files = FakeFileStore(root, mapOf(a to answer, b to caller))
        val removed = "package a\n"
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(answer to listOf(function(answer, "answer", 2, "(): Int")), caller to listOf(function(caller, "use", 4, "(): Int"))),
            references = mapOf((root.resolve(a) to Position(2, 4)) to listOf(SourceLocation(root.resolve(b), range(4, 17, 23)))),
            diagnosticsInContext = { file, _, open -> if (file == root.resolve(b) && open[root.resolve(a)] == removed) listOf(error(4, unresolved)) else null },
        )

        val result = Edit(files, intelligence)(Delete(a, "answer"))

        assertEquals(removed, files.content(a))
        assertEquals(listOf(b to unresolved), assertIs<EditResult.Written>(result).collateral.errors.map { it.file to it.message })
    }

    @Test
    fun `changing a member inside a replaced class reports the caller of that member`() = runTest {
        val box = "package a\n\nclass Box {\n    fun size(): Int = 1\n}\n"
        val longBox = box.replace("fun size(): Int = 1", "fun size(): Long = 1")
        fun boxSymbols(text: String, type: String) = listOf(
            CodeSymbol(
                "Box",
                SymbolKind.CLASS,
                TextRange(Position(2, 0), Position(4, 1)),
                range(2, 6, 9),
                listOf(
                    CodeSymbol("size", SymbolKind.FUNCTION, range(3, 4, text.lines()[3].length), range(3, 8, 12), parameterTypes = "", type = type),
                ),
            ),
        )
        val user = "package b\n\nimport a.Box\n\nfun use(): Int = Box().size()\n"
        val files = FakeFileStore(root, mapOf(a to box, b to user))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(box to boxSymbols(box, "(): Int"), longBox to boxSymbols(longBox, "(): Long"), user to listOf(function(user, "use", 4, "(): Int"))),
            references = mapOf((root.resolve(a) to Position(3, 8)) to listOf(SourceLocation(root.resolve(b), range(4, 23, 27)))),
            diagnosticsInContext = { file, _, open ->
                if (file == root.resolve(b) && open[root.resolve(a)] == longBox) listOf(error(4, "Return type mismatch")) else null
            },
        )

        val result = Edit(files, intelligence)(Replace(a, "Box", "class Box {\n    fun size(): Long = 1\n}"))

        assertEquals(listOf(Problem(b, "use", 5, "Return type mismatch", "fun use(): Int = Box().size()")), assertIs<EditResult.Written>(result).collateral.errors)
    }

    @Test
    fun `of more than 20 files with Usages, 20 are checked and the rest counted`() = runTest {
        val callers = (1..23).map { "src/C$it.kt" }
        val files = FakeFileStore(root, mapOf(a to answer) + callers.associateWith { caller })
        val intelligence = intelligence(references = callers.map { SourceLocation(root.resolve(it), range(4, 17, 23)) })

        val result = Edit(files, intelligence)(Replace(a, "answer", "fun answer(): String = \"1\""))

        val collateral = assertIs<EditResult.Written>(result).collateral
        assertEquals(3, collateral.filesUnchecked)
        assertEquals(callers.take(10), collateral.errors.map { it.file })
        assertEquals(10, collateral.moreErrors)
        assertEquals(callers.take(20).map(root::resolve).toSet(), intelligence.diagnosed.filterNot { it == root.resolve(a) }.toSet())
    }

    @Test
    fun `more than 10 Collateral Errors list 10 and count the rest`() = runTest {
        val intelligence = intelligence(newErrors = (1..12).map { error(4, "Error $it") })

        val result = Edit(files(), intelligence)(Replace(a, "answer", "fun answer(): String = \"1\""))

        val collateral = assertIs<EditResult.Written>(result).collateral
        assertEquals((1..10).map { "Error $it" }, collateral.errors.map { it.message })
        assertEquals(2, collateral.moreErrors)
    }

    @Test
    fun `a check that runs out of time still writes the Edit, and says it didn't finish`() = runTest {
        lateinit var fake: FakeCodeIntelligence
        fake = intelligence(beforeDiagnostics = { if (fake.diagnosed.last() == root.resolve(b)) delay(1.minutes) })
        val files = files()

        val result = Edit(files, fake, validationTimeout = 15.seconds, collateralTimeout = 5.seconds)(
            Replace(a, "answer", "fun answer(): String = \"1\""),
        )

        assertEquals(CollateralErrors(unfinished = CollateralErrors.Unfinished.TimedOut), assertIs<EditResult.Written>(result).collateral)
        assertEquals(answerString, files.content(a))
        fake.assertOneSessionClosedEverything()
    }

    @Test
    fun `a check that fails still writes the Edit, and says why it didn't finish`() = runTest {
        val files = files()

        val result = Edit(files, intelligence(failingDiagnostics = setOf(caller)))(Replace(a, "answer", "fun answer(): String = \"1\""))

        assertEquals(
            CollateralErrors(unfinished = CollateralErrors.Unfinished.Failed("diagnostics failed")),
            assertIs<EditResult.Written>(result).collateral,
        )
        assertEquals(answerString, files.content(a))
    }

    @Test
    fun `a rejected Edit looks for no Collateral Errors`() = runTest {
        val broken = "package a\n\nfun answer(): String = 1\n"
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(answer to listOf(function(answer, "answer", 2, "(): Int")), broken to listOf(function(broken, "answer", 2, "(): String"))),
            references = mapOf((root.resolve(a) to Position(2, 4)) to listOf(SourceLocation(root.resolve(b), range(4, 17, 23)))),
            diagnosticsByText = mapOf(broken to listOf(error(2, mismatch))),
        )

        val result = Edit(files(), intelligence)(Replace(a, "answer", "fun answer(): String = 1"))

        assertIs<EditResult.Rejected>(result)
        assertEquals(emptyList(), intelligence.referenced)
    }
}
