package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

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

        assertEquals(EditResult.ValidationFailed(ValidationFailure.Rejected(listOf(problem("greet"))), "e1"), rejected)
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

        assertEquals(EditResult.ValidationFailed(ValidationFailure.Rejected(listOf(problem("hello"))), "e2"), again)
        assertEquals(source, files.content(path))
        assertEquals(emptyList(), files.written)
        assertEquals(EditResult.NoRejectedEdit("e1", kept = "e2"), edit(listOf(importDuration), amend = "e1"))

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

        assertEquals(EditResult.WriteBackFailed(WriteBackFailure.ChangedOnDisk(path)), result)
        assertEquals("$source// theirs\n", files.content(path))
        assertEquals(emptyList(), files.written)
        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))
    }

    @Test
    fun `an amend after a file the Edit creates appeared on disk is refused, and nothing is written`() = runTest {
        val created = "src/B.kt"
        val code = "package a\n\nfun later() = Duration.ZERO\n"
        val creating = Edit(files, FakeCodeIntelligence(diagnosticsByText = mapOf(code to listOf(unresolved(2)))), writeLock = lock)
        assertIs<EditResult.ValidationFailed>(creating(listOf(Create(created, code))))
        files.writeOutside(created, "package a\n")

        val result = creating(listOf(EditIn(created, null, "package a\n", "package a\n\nimport kotlin.time.Duration\n")), amend = "e1")

        assertEquals(EditResult.WriteBackFailed(WriteBackFailure.ChangedOnDisk(created)), result)
        assertEquals("package a\n", files.content(created))
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `an amend after any other kt_edit or rename call, whatever its outcome, is refused, and nothing is written`() = runTest {
        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))

        edit(listOf(useDuration))
        assertIs<EditResult.OperationFailed>(edit(listOf(Delete(path, "com.example/greet"))))
        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))

        edit(listOf(useDuration))
        assertIs<RenameResult.InvalidName>(RenameSymbol(files, intelligence, writeLock = lock)("greet", "1st", path))
        assertEquals(EditResult.NoRejectedEdit("e2"), edit(listOf(importDuration), amend = "e2"))

        assertEquals(source, files.content(path))
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `an amend's Operation that fails is named by its index among the amend's Operations, and the Rejected Edit is kept`() = runTest {
        edit(listOf(useDuration))

        val result = edit(listOf(importDuration, EditIn(path, null, "absent", "present")), amend = "e1")

        val failed = assertIs<EditResult.OperationFailed>(result)
        assertEquals(1, failed.index)
        assertEquals(false, failed.inRejected)
        assertEquals("e1", failed.kept)
        assertEquals(source, files.content(path))
        assertIs<EditResult.Written>(edit(listOf(importDuration), amend = "e1"))
        assertEquals(fixed, files.content(path))
    }

    @Test
    fun `an amend whose edit_in starts inside indentation keeps the Rejected Edit, and the corrected amend writes the combined Edit`() = runTest {
        val other = "src/B.kt"
        files.writeOutside(other, "package a\n\nclass C {\n    val x = 1\n}\n")
        edit(listOf(useDuration))

        val result = edit(listOf(EditIn(other, null, "  val x = 1", "  val x = 2")), amend = "e1")

        assertEquals(EditResult.OperationFailed(0, OperationFailure.StartsInIndentation(EditInScope(null, other), 4), kept = "e1"), result)
        assertIs<EditResult.Written>(edit(listOf(importDuration), amend = "e1"))
        assertEquals(fixed, files.content(path))
    }

    @Test
    fun `an amend whose own Symbol Path is ambiguous keeps the Rejected Edit`() = runTest {
        val overloaded = "package a\n\nfun greet() = Duration.ZERO\n\nfun greet(x: Int) = 2\n"
        val overloading = Edit(
            files,
            FakeCodeIntelligence(
                symbolsByText = mapOf(
                    source to listOf(function("greet", 2, 15)),
                    broken to listOf(function("greet", 2, 27)),
                    fixed to listOf(function("greet", 4, 27)),
                    overloaded to listOf(function("greet", 2, 27), function("greet", 4, 21).copy(parameterTypes = "Int")),
                ),
                diagnosticsByText = mapOf(broken to listOf(unresolved(2))),
            ),
            writeLock = lock,
        )
        overloading(listOf(useDuration))

        val result = overloading(listOf(InsertAfter(path, "greet", "fun greet(x: Int) = 2"), EditIn(path, "greet", "ZERO", "ZERO")), amend = "e1")

        val failed = assertIs<EditResult.OperationFailed>(result, "$result")
        assertEquals(1, failed.index)
        assertIs<LookupFailure.Ambiguous>(assertIs<OperationFailure.LookupFailed>(failed.failure).failure)
        assertEquals("e1", failed.kept)
        assertIs<EditResult.Written>(overloading(listOf(importDuration), amend = "e1"))
        assertEquals(fixed, files.content(path))
    }

    @Test
    fun `an amend with an invalid import keeps the Rejected Edit`() = runTest {
        edit(listOf(useDuration))

        val result = edit(listOf(EditIn(path, null, "package a\n", "package a\n", imports = listOf("not an import"))), amend = "e1")

        assertEquals(EditResult.OperationFailed(0, OperationFailure.InvalidImport("not an import"), kept = "e1"), result)
        assertIs<EditResult.Written>(edit(listOf(importDuration), amend = "e1"))
    }

    @Test
    fun `an amend that finds the index incomplete keeps the Rejected Edit`() = runTest {
        edit(listOf(useDuration))
        val indexing = Edit(files, FakeCodeIntelligence(notReadyPhase = "Indexing"), readinessTimeout = 5.seconds, writeLock = lock)

        val result = indexing(listOf(importDuration), amend = "e1")

        assertEquals(EditResult.NotReady(Readiness.NotReady("Indexing", 5.seconds), kept = "e1"), result)
        assertIs<EditResult.Written>(edit(listOf(importDuration), amend = "e1"))
    }

    @Test
    fun `an amend naming another id keeps the current Rejected Edit, and says so`() = runTest {
        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))
        edit(listOf(useDuration))
        edit(listOf(EditIn(path, null, "greet", "hello")), amend = "e1")

        assertEquals(EditResult.NoRejectedEdit("e1", kept = "e2"), edit(listOf(importDuration), amend = "e1"))
        assertEquals(EditResult.NoRejectedEdit("e7", kept = "e2"), edit(listOf(importDuration), amend = "e7"))
        assertIs<EditResult.Written>(edit(listOf(importDuration), amend = "e2"))
        assertEquals(renamedFixed, files.content(path))
    }

    @Test
    fun `an amend whose Rejected Edit no longer applies drops it`() = runTest {
        edit(listOf(useDuration))
        val renamed = Edit(
            files,
            FakeCodeIntelligence(symbolsByText = mapOf(broken to emptyList()), diagnosticsByText = mapOf(broken to listOf(unresolved(2)))),
            writeLock = lock,
        )
        edit(listOf(Replace(path, "greet", "fun greet() = 1"), useDuration))

        val result = renamed(listOf(importDuration), amend = "e2")

        assertEquals(true, assertIs<EditResult.OperationFailed>(result, "$result").inRejected)
        assertEquals(null, result.kept)
        assertEquals(EditResult.NoRejectedEdit("e2"), edit(listOf(importDuration), amend = "e2"))
    }

    @Test
    fun `a successful amend drops the Rejected Edit`() = runTest {
        edit(listOf(useDuration))
        assertIs<EditResult.Written>(edit(listOf(importDuration), amend = "e1"))

        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))
    }

    @Test
    fun `an amend whose Validation times out drops the Rejected Edit`() = runTest {
        edit(listOf(useDuration))
        val slow = Edit(
            files,
            FakeCodeIntelligence(symbolsByText = mapOf(fixed to listOf(function("greet", 4, 27)), broken to listOf(function("greet", 2, 27))), diagnosticsDelay = 10.seconds),
            validationTimeout = 15.seconds,
            writeLock = lock,
        )
        assertEquals(EditResult.ValidationFailed(ValidationFailure.TimedOut), slow(listOf(importDuration), amend = "e1"))

        assertEquals(EditResult.NoRejectedEdit("e1"), edit(listOf(importDuration), amend = "e1"))
    }

    @Test
    fun `an amend whose insert has blank code keeps the Rejected Edit`() = runTest {
        edit(listOf(useDuration))

        val result = edit(listOf(InsertAfter(path, "greet", "  \n")), amend = "e1")

        assertEquals(EditResult.OperationFailed(0, OperationFailure.BlankCode, kept = "e1"), result)
        assertIs<EditResult.Written>(edit(listOf(importDuration), amend = "e1"))
    }
}
