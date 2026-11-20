package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** An Edit that amends the last Rejected Edit with only the Operations that fix it. */
class EditAmendTest {
    private val root = Path.of("/project")
    private val path = "src/A.kt"
    private val source = "package a\n\nfun greet() = 1\n"

    /** Uses Duration without importing it. */
    private val broken = "package a\n\nfun greet() = Duration.ZERO\n"
    private val fixed = "package a\n\nimport kotlin.time.Duration\n\nfun greet() = Duration.ZERO\n"
    private val renamedBroken = "package a\n\nfun hello() = Duration.ZERO\n"
    private val renamedFixed = "package a\n\nimport kotlin.time.Duration\n\nfun hello() = Duration.ZERO\n"

    private val useDuration = EditIn(path, null, "= 1", "= Duration.ZERO")
    private val importDuration = EditIn(path, null, "package a\n", "package a\n\nimport kotlin.time.Duration\n")

    private fun range(line: Int, start: Int, end: Int) = TextRange(Position(line, start), Position(line, end))

    private fun function(name: String, line: Int, length: Int) =
        CodeSymbol(name, SymbolKind.FUNCTION, range(line, 0, length), range(line, 4, 4 + name.length), emptyList(), parameterTypes = "")

    private fun unresolved(line: Int) = Diagnostic(range(line, 14, 22), Severity.ERROR, "Unresolved reference 'Duration'.", "UNRESOLVED_REFERENCE")

    private fun problem(name: String) = Problem(path, name, 3, "Unresolved reference 'Duration'.", "fun $name() = Duration.ZERO")

    private val intelligence = FakeCodeIntelligence(
        symbolsByText = mapOf(
            source to listOf(function("greet", 2, 15)),
            broken to listOf(function("greet", 2, 27)),
            fixed to listOf(function("greet", 4, 27)),
            renamedBroken to listOf(function("hello", 2, 27)),
            renamedFixed to listOf(function("hello", 4, 27)),
        ),
        diagnosticsByText = mapOf(broken to listOf(unresolved(2)), renamedBroken to listOf(unresolved(2))),
    )
    private val files = FakeFileStore(root, mapOf(path to source))
    private val lock = WriteLock()
    private val edit = Edit(files, intelligence, writeLock = lock)

    @Test
    fun `a rejected Edit carries an id, and an amend with only the fixing Operation writes the combined Edit`() = runTest {
        val rejected = edit(listOf(useDuration))

        assertEquals(EditResult.Rejected(listOf(problem("greet")), "e1"), rejected)
        assertEquals(source, files.content(path))

        val result = edit(listOf(importDuration), amend = "e1")

        assertEquals(fixed, files.content(path))
        val written = assertIs<EditResult.Written>(result, "$result").files.single()
        assertEquals(path, written.file)
        assertEquals(listOf(EditedSymbol("greet", 5, 5)), written.symbols)
    }

    @Test
    fun `a failing amend is rejected with the combined Edit's errors and a new id, which can be amended in turn`() = runTest {
        edit(listOf(useDuration))

        val again = edit(listOf(EditIn(path, null, "greet", "hello")), amend = "e1")

        assertEquals(EditResult.Rejected(listOf(problem("hello")), "e2"), again)
        assertEquals(source, files.content(path))
        assertEquals(emptyList(), files.written)
        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))

        edit(listOf(useDuration))
        edit(listOf(EditIn(path, null, "greet", "hello")), amend = "e3")
        val result = edit(listOf(importDuration), amend = "e4")

        assertIs<EditResult.Written>(result, "$result")
        assertEquals(renamedFixed, files.content(path))
    }

    @Test
    fun `an amend after a file the Edit touched changed on disk is refused, and nothing is written`() = runTest {
        edit(listOf(useDuration))
        files.writeOutside(path, "$source// theirs\n")

        val result = edit(listOf(importDuration), amend = "e1")

        assertEquals(EditResult.ChangedOnDisk(path), result)
        assertEquals("$source// theirs\n", files.content(path))
        assertEquals(emptyList(), files.written)
        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))
    }

    @Test
    fun `an amend after a file the Edit creates appeared on disk is refused, and nothing is written`() = runTest {
        val created = "src/B.kt"
        val code = "package a\n\nfun later() = Duration.ZERO\n"
        val creating = Edit(files, FakeCodeIntelligence(diagnosticsByText = mapOf(code to listOf(unresolved(2)))), writeLock = lock)
        assertIs<EditResult.Rejected>(creating(listOf(Create(created, code))))
        files.writeOutside(created, "package a\n")

        val result = creating(listOf(EditIn(created, null, "package a\n", "package a\n\nimport kotlin.time.Duration\n")), amend = "e1")

        assertEquals(EditResult.ChangedOnDisk(created), result)
        assertEquals("package a\n", files.content(created))
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `an amend after any other kt_edit or rename call, or with an unknown id, is refused, and nothing is written`() = runTest {
        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))

        edit(listOf(useDuration))
        assertEquals(EditResult.NoRejectedEdit("e7"), edit(listOf(importDuration), amend = "e7"))
        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))

        edit(listOf(useDuration))
        assertIs<EditResult.OperationFailed>(edit(listOf(Delete(path, "com.example/greet"))))
        assertEquals(EditResult.NoRejectedEdit("e2"), edit(listOf(importDuration), amend = "e2"))

        edit(listOf(useDuration))
        assertIs<RenameResult.InvalidName>(RenameSymbol(files, intelligence, writeLock = lock)("greet", "1st", path))
        assertEquals(EditResult.NoRejectedEdit("e3"), edit(listOf(importDuration), amend = "e3"))

        assertEquals(source, files.content(path))
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `an amend's Operation that fails is named by its index among the amend's Operations`() = runTest {
        edit(listOf(useDuration))

        val result = edit(listOf(importDuration, EditIn(path, null, "absent", "present")), amend = "e1")

        val failed = assertIs<EditResult.OperationFailed>(result)
        assertEquals(1, failed.index)
        assertEquals(false, failed.inRejected)
        assertEquals(source, files.content(path))
    }
}
