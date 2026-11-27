package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.range
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * An Edit that amends the last Rejected Edit with only the Operations that fix it: the combined Edit applied, re-checked
 * against the disk and validated. What each outcome keeps of the Rejected Edit is [WriteCallsTest]'s.
 */
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
    private val calls = WriteCalls()
    private val edit = Edit(files, intelligence, writeCalls = calls)

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

        val result = edit(listOf(importDuration), amend = "e2")

        assertIs<EditResult.Written>(result, "$result")
        assertEquals(renamedFixed, files.content(path))
    }

    @Test
    fun `an amend after a file the Edit touched changed on disk is refused, drops the Rejected Edit, and writes nothing`() = runTest {
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
        val creating = Edit(files, FakeCodeIntelligence(diagnosticsByText = mapOf(code to listOf(unresolved(2)))), writeCalls = calls)
        assertIs<EditResult.ValidationFailed>(creating(listOf(Create(created, code))))
        files.writeOutside(created, "package a\n")

        val result = creating(listOf(EditIn(created, null, "package a\n", "package a\n\nimport kotlin.time.Duration\n")), amend = "e1")

        assertEquals(EditResult.WriteBackFailed(WriteBackFailure.ChangedOnDisk(created)), result)
        assertEquals("package a\n", files.content(created))
        assertEquals(emptyList(), files.written)
    }

    @Test
    fun `an amend whose Rejected Edit no longer applies names it and drops it`() = runTest {
        edit(listOf(useDuration))
        val renamed = Edit(
            files,
            FakeCodeIntelligence(symbolsByText = mapOf(broken to emptyList()), diagnosticsByText = mapOf(broken to listOf(unresolved(2)))),
            writeCalls = calls,
        )
        edit(listOf(Replace(path, "greet", "fun greet() = 1"), useDuration))

        val result = renamed(listOf(importDuration), amend = "e2")

        assertEquals(true, assertIs<EditResult.OperationFailed>(result, "$result").inRejected)
        assertEquals(null, result.rejectedEdit)
    }

    @Test
    fun `an amend whose Validation times out drops the Rejected Edit`() = runTest {
        edit(listOf(useDuration))
        val slow = Edit(
            files,
            FakeCodeIntelligence(symbolsByText = mapOf(fixed to listOf(function("greet", 4, 27)), broken to listOf(function("greet", 2, 27))), diagnosticsDelay = 10.seconds),
            validationTimeout = 15.seconds,
            writeCalls = calls,
        )

        assertEquals(EditResult.ValidationFailed(ValidationFailure.TimedOut), slow(listOf(importDuration), amend = "e1"))
    }
}
