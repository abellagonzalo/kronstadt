package kronstadt.server

import kronstadt.core.application.LookupFailure
import kronstadt.core.domain.SymbolCandidate
import kotlin.test.Test
import kotlin.test.assertEquals

/** The one wording every tool uses for a Symbol Path or file that names no one Symbol. */
class LookupFailuresTest {
    private val a = SymbolCandidate("Greeter/greet", "src/A.kt")
    private val b = SymbolCandidate("greet", "src/B.kt")

    @Test
    fun `an invalid path quotes it and the reason`() {
        assertEquals(
            "Invalid Symbol Path 'a/(b': unbalanced parenthesis",
            describe(LookupFailure.InvalidPath("a/(b", "unbalanced parenthesis"), "kt_read_symbol"),
        )
    }

    @Test
    fun `an ambiguous path lists the candidates, with their files when resolved project-wide`() {
        assertEquals(
            "'greet' matches 2 symbols in src/A.kt; pass one of:\n  Greeter/greet\n  greet",
            describe(LookupFailure.Ambiguous("greet", "src/A.kt", listOf(a, b)), "kt_read_symbol"),
        )
        assertEquals(
            "'greet' matches 2 symbols; pass one of these paths with its file:\n  Greeter/greet — src/A.kt\n  greet — src/B.kt",
            describe(LookupFailure.Ambiguous("greet", null, listOf(a, b)), "kt_edit"),
        )
    }

    @Test
    fun `kt_find_usages, resolving project-wide, asks for one of the paths with its file as declaredIn`() {
        assertEquals(
            "'greet' matches 2 symbols; pass one of these paths with its file as declaredIn:\n" +
                "  Greeter/greet — src/A.kt\n  greet — src/B.kt",
            describe(LookupFailure.Ambiguous("greet", null, listOf(a, b)), "kt_find_usages"),
        )
    }

    @Test
    fun `kt_find_usages, resolving in declaredIn, asks for one of the paths in it`() {
        assertEquals(
            "'greet' matches 2 symbols in src/A.kt; pass one of:\n  Greeter/greet\n  greet",
            describe(LookupFailure.Ambiguous("greet", "src/A.kt", listOf(a, b)), "kt_find_usages"),
        )
    }

    @Test
    fun `a missing Symbol lists close names, or says which tool to look with`() {
        assertEquals(
            "No symbol 'gret' in src/A.kt. Close names:\n  Greeter/greet",
            describe(LookupFailure.NotFound("gret", "src/A.kt", listOf(a)), "kt_read_symbol"),
        )
        assertEquals(
            "No symbol 'gret'. Close names:\n  Greeter/greet — src/A.kt",
            describe(LookupFailure.NotFound("gret", null, listOf(a)), "kt_get_definition"),
        )
        assertEquals(
            "No symbol 'gret' in src/A.kt. Use kt_read_structure to list its declarations.",
            describe(LookupFailure.NotFound("gret", "src/A.kt", emptyList()), "kt_edit"),
        )
        assertEquals(
            "No symbol 'gret'. Use kt_find_symbol to search by partial name.",
            describe(LookupFailure.NotFound("gret", null, emptyList()), "kt_read_symbol"),
        )
    }

    @Test
    fun `kt_find_usages, resolving project-wide, offers declaredIn for a missing Symbol too`() {
        assertEquals(
            "No symbol 'gret'. Use kt_find_symbol to search by partial name, or, if you know the file that declares it, " +
                "pass it as declaredIn.",
            describe(LookupFailure.NotFound("gret", null, emptyList()), "kt_find_usages"),
        )
        assertEquals(
            "No symbol 'gret' in src/A.kt. Use kt_read_structure to list its declarations.",
            describe(LookupFailure.NotFound("gret", "src/A.kt", emptyList()), "kt_find_usages"),
        )
    }

    @Test
    fun `too many declarations asks for the file, as declaredIn for kt_find_usages`() {
        val cause = "'greet' can't be resolved without a file: so many declarations are named 'greet', dependencies' " +
            "included, that the project index stops at a maximum number of results and may not list them all; "
        assertEquals(cause + "pass its file", describe(LookupFailure.TooManyDeclarations("greet", "greet"), "kt_rename_symbol"))
        assertEquals(
            cause + "pass the file that declares it as declaredIn",
            describe(LookupFailure.TooManyDeclarations("greet", "greet"), "kt_find_usages"),
        )
    }

    @Test
    fun `an untrustworthy span says to retry, to read the enclosing Symbol, or to edit_in the whole file`() {
        assertEquals(
            "Index out of sync for greet in src/A.kt: its name is not where kotlin-lsp says; retry",
            describe(LookupFailure.OutOfSync("greet", "src/A.kt"), "kt_read_symbol"),
        )
        assertEquals(
            "Box/width shares line 3 of src/A.kt with other code, so it has no whole-line span; read the enclosing symbol instead",
            describe(LookupFailure.SharedLine("Box/width", "src/A.kt", 3), "kt_read_symbol"),
        )
        assertEquals(
            "Box/width shares line 3 of src/A.kt with other code, so it has no whole-line span; use a whole-file edit_in " +
                "instead: file 'src/A.kt' with no symbol, and an 'old' of exact text from that file",
            describe(LookupFailure.SharedLine("Box/width", "src/A.kt", 3), "kt_edit"),
        )
        assertEquals(
            "Box/constructor(Int, Int) shares line 1 of src/A.kt with other code, so it has no whole-line span; use a " +
                "whole-file edit_in instead: file 'src/A.kt' with no symbol, and an 'old' of exact text from that file",
            describe(LookupFailure.SharedLine("Box/constructor(Int, Int)", "src/A.kt", 1), "kt_edit"),
        )
    }

    @Test
    fun `a file that can't be looked in says why, naming the tool for a non-Kotlin one`() {
        assertEquals("File not found: src/Missing.kt", describe(LookupFailure.FileNotFound("src/Missing.kt"), "kt_read_structure"))
        assertEquals(
            "Not a Kotlin file: README.md. kt_find_usages only supports .kt and .kts files.",
            describe(LookupFailure.NotKotlin("README.md"), "kt_find_usages"),
        )
        assertEquals("Path is outside the project root: ../B.kt", describe(LookupFailure.OutsideProject("../B.kt"), "kt_edit"))
    }

    @Test
    fun `a file that can't be looked in names the argument it was passed as, when the tool takes two`() {
        assertEquals(
            "declaredIn: File not found: src/Missing.kt",
            describe(LookupFailure.FileNotFound("src/Missing.kt"), "kt_find_usages", argument = "declaredIn"),
        )
        assertEquals(
            "file: Path is outside the project root: ../B.kt",
            describe(LookupFailure.OutsideProject("../B.kt"), "kt_find_usages", argument = "file"),
        )
    }

    @Test
    fun `a message becomes a sentence more can follow`() {
        assertEquals("File not found: src/A.kt.", sentence("File not found: src/A.kt"))
        assertEquals("No symbol 'x'. Use kt_find_symbol to search by partial name.", sentence("No symbol 'x'. Use kt_find_symbol to search by partial name."))
        assertEquals("No symbol 'x'. Close names:\n  y\n", sentence("No symbol 'x'. Close names:\n  y"))
    }
}
