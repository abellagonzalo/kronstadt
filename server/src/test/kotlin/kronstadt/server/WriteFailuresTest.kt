package kronstadt.server

import kronstadt.core.application.Problem
import kronstadt.core.application.ValidationFailure
import kronstadt.core.application.WriteBackFailure
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one wording kt_edit and kt_rename_symbol use when Validation or Write-back lets nothing (or not all) be written. */
class WriteFailuresTest {
    private val typeMismatch = Problem("src/A.kt", "greet", 5, "Type mismatch", "\"hello\"")
    private val ambiguity = Problem("src/B.kt", null, 7, "Overload resolution ambiguity", "Greeter().hello()")

    @Test
    fun `a rejected Edit lists its new errors with their files and offers its id to amend`() {
        assertEquals(
            "Edit rejected (id: e1): 1 new error; nothing written.\n" +
                "  src/A.kt:L5 in greet: Type mismatch\n" +
                "      \"hello\"\n" +
                "Fix with kt_edit amend: \"e1\" and only the fixing Operations, or resend the whole batch.",
            describe(ValidationFailure.Rejected(listOf(typeMismatch)), "Edit", "e1"),
        )
    }

    @Test
    fun `a rejected change without an id, as a rename, lists its new errors and asks to fix what causes them`() {
        assertEquals(
            "Rename rejected: 2 new errors; nothing written.\n" +
                "  src/A.kt:L5 in greet: Type mismatch\n" +
                "      \"hello\"\n" +
                "  src/B.kt:L7 in (file level): Overload resolution ambiguity\n" +
                "      Greeter().hello()\n" +
                "Fix what causes them and retry.",
            describe(ValidationFailure.Rejected(listOf(typeMismatch, ambiguity)), "Rename"),
        )
    }

    @Test
    fun `a Validation that timed out says nothing was written`() {
        assertEquals("validation timed out; nothing written", describe(ValidationFailure.TimedOut, "Edit"))
        assertEquals("validation timed out; nothing written", describe(ValidationFailure.TimedOut, "Rename"))
    }

    @Test
    fun `a file changed on disk is to be re-read`() {
        assertEquals("src/A.kt changed on disk since read; re-read and retry", describe(WriteBackFailure.ChangedOnDisk("src/A.kt")))
    }

    @Test
    fun `a partial Write-back names the file that failed, why, and those already written`() {
        assertEquals(
            "Write-back failed part-way: writing src/C.kt failed (No space left on device). " +
                "Already written, not rolled back: src/A.kt, src/B.kt. " +
                "Nothing else was written; re-read these files before retrying.",
            describe(WriteBackFailure.PartiallyWritten(listOf("src/A.kt", "src/B.kt"), "src/C.kt", "No space left on device")),
        )
    }

    @Test
    fun `a file renamed onto an existing one names both`() {
        assertEquals(
            "src/a/Greeter.kt would be renamed to src/a/Host.kt, which already exists; nothing written. " +
                "Pick another name, or move that file first.",
            describe(WriteBackFailure.TargetExists("src/a/Greeter.kt", "src/a/Host.kt")),
        )
    }
}
