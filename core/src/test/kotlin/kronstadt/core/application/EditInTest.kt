package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** `edit_in` Operations of an Edit: an exact string replaced once, inside a Symbol Span or a whole file. */
class EditInTest {
    private val root = Path.of("/project")
    private val path = "src/A.kt"

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    private fun function(name: String, firstLine: Int, lastLine: Int) = CodeSymbol(
        name,
        SymbolKind.FUNCTION,
        range(firstLine, 0, lastLine, 1),
        range(firstLine, 4, firstLine, 4 + name.length),
        emptyList(),
        parameterTypes = "",
        type = "(): Int",
    )

    private val source = """
        |package a
        |
        |import a.b.Unused
        |
        |fun greet(): Int {
        |    val n = 1
        |    return n
        |}
        |
        |fun other(): Int {
        |    val n = 1
        |    return n
        |}
        |""".trimMargin()
    private val symbols = listOf(function("greet", 4, 7), function("other", 9, 12))

    /** Runs [operations] on a file holding [source]; the fake reports [symbols] for it, else those [texts] give. */
    private suspend fun run(texts: Map<String, List<CodeSymbol>>, vararg operations: Operation): Pair<EditResult, FakeFileStore> {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = FakeCodeIntelligence(documentSymbols = symbols, symbolsByText = texts)
        val result = Edit(files, intelligence)(operations.toList())
        intelligence.assertOneSessionClosedEverything()
        return result to files
    }

    @Test
    fun `edit_in on a Symbol replaces the one occurrence inside its span, literally`() = runTest {
        // "val n = 1" also occurs in other(): only greet()'s span counts.
        val expected = source.replaceFirst("    val n = 1\n", "    val n =\n  2\n")
        val texts = mapOf(expected to listOf(function("greet", 4, 8), function("other", 10, 13)))

        val (result, files) = run(texts, EditIn(path, "greet", old = "val n = 1", new = "val n =\n  2"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 5, 9)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `edit_in on a whole file matches text outside any Symbol, such as an import to remove`() = runTest {
        val expected = """
            |package a.renamed
            |
            |
            |fun greet(): Int {
            |    val n = 1
            |    return n
            |}
            |
            |fun other(): Int {
            |    val n = 1
            |    return n
            |}
            |""".trimMargin()
        val texts = mapOf(expected to listOf(function("greet", 3, 6), function("other", 8, 11)))

        val (result, files) = run(
            texts,
            EditIn(path, null, old = "import a.b.Unused\n", new = ""),
            EditIn(path, null, old = "package a\n", new = "package a.renamed\n"),
        )

        assertEquals(expected, files.content(path))
        // No Symbol was edited, so none is listed.
        assertEquals(EditResult.Written(listOf(EditedFile(path, emptyList(), emptyList(), 0, 0))), result)
    }

    @Test
    fun `edit_in whose text isn't in the Symbol's span rejects the Edit, even if it is elsewhere in the file`() = runTest {
        val (result, files) = run(
            emptyMap(),
            EditIn(path, null, old = "package a", new = "package b"),
            EditIn(path, "greet", old = "import a.b.Unused", new = ""),
        )

        assertEquals(EditResult.OperationFailed(1, OperationFailure.NotExactlyOnce("import a.b.Unused", "greet", path, 0, emptyList())), result)
        assertEquals(source, files.content(path))
    }

    @Test
    fun `edit_in matches whitespace exactly, so a differently indented text doesn't match`() = runTest {
        val (result, _) = run(emptyMap(), EditIn(path, "greet", old = "  val n = 1\n  return n", new = ""))

        assertEquals(
            EditResult.OperationFailed(0, OperationFailure.NotExactlyOnce("  val n = 1\n  return n", "greet", path, 0, emptyList())),
            result,
        )
    }

    @Test
    fun `edit_in text that splits a CRLF line break doesn't match`() = runTest {
        val crlf = "package a\r\n\r\nfun greet(): Int = 1\r\n"
        val files = FakeFileStore(root, mapOf(path to crlf))
        val intelligence = FakeCodeIntelligence(documentSymbols = listOf(function("greet", 2, 2)))

        val result = Edit(files, intelligence)(EditIn(path, null, old = "package a\r", new = "package b\r"))

        assertEquals(EditResult.OperationFailed(0, OperationFailure.NotExactlyOnce("package a\r", null, path, 0, emptyList())), result)
        assertEquals(crlf, files.content(path))
    }

    @Test
    fun `edit_in whose text occurs several times rejects the Edit and lists where`() = runTest {
        val (result, files) = run(emptyMap(), EditIn(path, null, old = "val n = 1", new = "val n = 2"))

        assertEquals(
            EditResult.OperationFailed(
                0,
                OperationFailure.NotExactlyOnce("val n = 1", null, path, 2, listOf(Occurrence(6, "val n = 1"), Occurrence(11, "val n = 1"))),
            ),
            result,
        )
        assertEquals(source, files.content(path))
    }

    @Test
    fun `edit_in whose text starts inside a line's indentation rejects the Edit, naming the line`() = runTest {
        // greet()'s body is indented 4 spaces; this 'old' starts after 2 of them.
        val (result, files) = run(emptyMap(), EditIn(path, "greet", old = "  val n = 1\n", new = "  val m = 1\n  val n = m\n"))

        assertEquals(EditResult.OperationFailed(0, OperationFailure.StartsInIndentation("greet", path, 6)), result)
        assertEquals(source, files.content(path))
    }

    @Test
    fun `edit_in on a whole file whose text starts inside a line's indentation rejects the Edit too`() = runTest {
        val (result, files) = run(emptyMap(), EditIn(path, null, old = "  return n\n}\n\nfun other", new = "  return 2\n}\n\nfun other"))

        assertEquals(EditResult.OperationFailed(0, OperationFailure.StartsInIndentation(null, path, 7)), result)
        assertEquals(source, files.content(path))
    }

    @Test
    fun `edit_in whose text starts at the beginning of an indented line splices it as written`() = runTest {
        val expected = source.replaceFirst("    val n = 1\n", "    val m = 1\n    val n = m\n")
        val texts = mapOf(expected to listOf(function("greet", 4, 8), function("other", 10, 13)))

        val (result, files) = run(texts, EditIn(path, "greet", old = "    val n = 1\n", new = "    val m = 1\n    val n = m\n"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 5, 9)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `edit_in whose text starts after the indentation or mid-line splices it as written`() = runTest {
        val expected = source.replaceFirst("    val n = 1\n    return n\n", "    val m = 1\n    return m + 1\n")
        val texts = mapOf(expected to symbols)

        val (result, files) = run(
            texts,
            EditIn(path, "greet", old = "val n = 1\n", new = "val m = 1\n"),
            EditIn(path, "greet", old = "n\n}", new = "m + 1\n}"),
        )

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 5, 8)), emptyList(), 0, 0))), result)
    }
}
