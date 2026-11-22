package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ValidationTest {
    private fun error(line: Int, message: String) =
        Diagnostic(TextRange(Position(line, 0), Position(line, 1)), Severity.ERROR, message, "ERR")

    @Test
    fun `the errors already there are exactly those introduced doesn't count as new, even when they share a key`() {
        val before = listOf(error(1, "x"), error(2, "y"))
        val after = listOf(error(5, "x"), error(3, "x"), error(2, "y"), Diagnostic(TextRange(Position(4, 0), Position(4, 1)), Severity.WARNING, "x", "ERR"))
        val edited = listOf(5..5)

        assertEquals(listOf(error(5, "x")), Validation.introduced(before, after, Severity.ERROR, edited))
        assertEquals(listOf(error(2, "y"), error(3, "x")), Validation.preexisting(before, after, Severity.ERROR, edited))
    }
}
