package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

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
    fun `edit_in on a whole file that changes lines inside a Symbol lists that Symbol, as one on the Symbol would`() = runTest {
        val expected = source.replaceFirst("    val n = 1\n", "    val n = 2\n")
        val texts = mapOf(expected to symbols)

        val (result, files) = run(texts, EditIn(path, null, old = "greet(): Int {\n    val n = 1", new = "greet(): Int {\n    val n = 2"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 5, 8)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `edit_in on a whole file that changes lines inside a member lists the member, not its class`() = runTest {
        val file = "package a\n\nclass Box {\n    fun width(): Int {\n        return 1\n    }\n}\n"
        val expected = file.replace("return 1", "return 2")
        val width = CodeSymbol("width", SymbolKind.FUNCTION, range(3, 4, 5, 5), range(3, 8, 3, 13), parameterTypes = "", type = "(): Int")
        val box = listOf(CodeSymbol("Box", SymbolKind.CLASS, range(2, 0, 6, 1), range(2, 6, 2, 9), listOf(width)))
        val files = FakeFileStore(root, mapOf(path to file))
        val intelligence = FakeCodeIntelligence(documentSymbols = box)

        val result = Edit(files, intelligence)(listOf(EditIn(path, null, old = "return 1", new = "return 2")))

        intelligence.assertOneSessionClosedEverything()
        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("width", 4, 6)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `edit_in whose text isn't in the Symbol's span rejects the Edit, even if it is elsewhere in the file`() = runTest {
        val (result, files) = run(
            emptyMap(),
            EditIn(path, null, old = "package a", new = "package b"),
            EditIn(path, "greet", old = "import a.b.Unused", new = ""),
        )

        assertEquals(EditResult.OperationFailed(1, OperationFailure.NotExactlyOnce("import a.b.Unused", EditInScope("greet", path), 0, emptyList())), result)
        assertEquals(source, files.content(path))
    }

    @Test
    fun `edit_in matches whitespace exactly, so a differently indented text doesn't match, but says where it would`() = runTest {
        val (result, files) = run(emptyMap(), EditIn(path, "greet", old = "  val n = 1\n  return n", new = ""))

        assertEquals(
            EditResult.OperationFailed(
                0,
                OperationFailure.NotExactlyOnce(
                    "  val n = 1\n  return n",
                    EditInScope("greet", path),
                    0,
                    emptyList(),
                    IndentationHint(6, IndentationHint.Indents(file = "    ", snippet = "  ")),
                ),
            ),
            result,
        )
        assertEquals(source, files.content(path))
    }

    /** The indentation hint of [result], an Edit failed on an `edit_in` that occurs nowhere. */
    private fun hintOf(result: EditResult): IndentationHint? {
        val failure = assertIs<OperationFailure.NotExactlyOnce>(assertIs<EditResult.OperationFailed>(result).failure)
        assertEquals(0, failure.count)
        return failure.indentation
    }

    @Test
    fun `edit_in text whose lines are each indented differently from the file's gets a hint without indents`() = runTest {
        val (result, _) = run(emptyMap(), EditIn(path, "greet", old = "  val n = 1\n      return n", new = ""))

        assertEquals(IndentationHint(6, null), hintOf(result))
    }

    @Test
    fun `edit_in on a whole file gets the indentation hint too, but not when the text occurs more than once that way`() = runTest {
        val (once, _) = run(emptyMap(), EditIn(path, null, old = "fun other(): Int {\n  val n = 1", new = ""))
        val (twice, _) = run(emptyMap(), EditIn(path, null, old = "  val n = 1\n  return n", new = ""))

        assertEquals(IndentationHint(10, IndentationHint.Indents(file = "    ", snippet = "  ")), hintOf(once))
        assertEquals(null, hintOf(twice))
    }

    @Test
    fun `edit_in text that differs in more than indentation gets no hint`() = runTest {
        val (result, _) = run(emptyMap(), EditIn(path, "greet", old = "  val n = 2\n  return n", new = ""))

        assertEquals(null, hintOf(result))
    }

    /** Runs [operation] on a CRLF file holding [crlf], whose symbols the fake gives by text in [texts]. */
    private suspend fun runCrlf(crlf: String, texts: Map<String, List<CodeSymbol>>, operation: Operation): Pair<EditResult, FakeFileStore> {
        val files = FakeFileStore(root, mapOf(path to crlf))
        val intelligence = FakeCodeIntelligence(symbolsByText = texts)
        val result = Edit(files, intelligence)(operation)
    intelligence.assertOneSessionClosedEverything()
    return result to files
    }

    private val crlf = "package a\r\n\r\nfun greet(): Int {\r\n    val n = 1\r\n    return n\r\n}\r\n"

    @Test
    fun `edit_in text with LF line breaks matches in a CRLF file, which keeps CRLF throughout`() = runTest {
        val expected = "package a\r\n\r\nfun greet(): Int {\r\n    val n = 2\r\n    return n + 1\r\n}\r\n"
        val texts = mapOf(crlf to listOf(function("greet", 2, 5)), expected to listOf(function("greet", 2, 5)))

        val (result, files) = runCrlf(crlf, texts, EditIn(path, "greet", old = "val n = 1\n    return n", new = "val n = 2\n    return n + 1"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 3, 6)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `edit_in text with CRLF line breaks matches in an LF file`() = runTest {
        val expected = source.replaceFirst("    val n = 1\n    return n\n", "    return 1\n")
        val texts = mapOf(expected to listOf(function("greet", 4, 6), function("other", 8, 11)))

        val (result, files) = run(texts, EditIn(path, "greet", old = "val n = 1\r\n    return n", new = "return 1"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 5, 7)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `edit_in in a CRLF file still matches indentation exactly`() = runTest {
        val (result, files) = runCrlf(crlf, mapOf(crlf to listOf(function("greet", 2, 5))), EditIn(path, "greet", old = "val n = 1\n  return n", new = ""))

        assertEquals(IndentationHint(4, IndentationHint.Indents(file = "    ", snippet = "  ")), hintOf(result))
        assertEquals(crlf, files.content(path))
    }

    @Test
    fun `edit_in on a whole CRLF file replaces only the lines its multi-line text is on`() = runTest {
        val file = "package a\r\n\r\n// one\r\n// two\r\n// three\r\n\r\nfun greet(): Int = 1\r\n"
        val expected = "package a\r\n\r\n// one and two\r\n// three\r\n\r\nfun greet(): Int = 1\r\n"
        val texts = mapOf(file to listOf(function("greet", 6, 6)), expected to listOf(function("greet", 5, 5)))

        val (result, files) = runCrlf(file, texts, EditIn(path, null, old = "one\n// two", new = "one and two"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, emptyList(), emptyList(), 0, 0))), result)
    }

    @Test
    fun `edit_in text ending in a lone CR matches a whole CRLF line break`() = runTest {
        val file = "package a\r\n\r\nfun greet(): Int = 1\r\n"
        val expected = "package b\r\n\r\nfun greet(): Int = 1\r\n"
        val texts = mapOf(file to listOf(function("greet", 2, 2)), expected to listOf(function("greet", 2, 2)))

        val (result, files) = runCrlf(file, texts, EditIn(path, null, old = "package a\r", new = "package b\r"))

        assertEquals(expected, files.content(path))
        assertIs<EditResult.Written>(result)
    }

    @Test
    fun `edit_in text's line break never matches half of a CRLF, so it occurs once`() = runTest {
        val file = "package a\r\n\r\nfun greet(): Int = 1\r\n"
        // If a "\n" could take the "\n" half of the first "\r\n", this would also occur from there, twice in all.
        val expected = "package a\r\n\r\n\r\nfun greet(): Int = 1\r\n"
        val texts = mapOf(file to listOf(function("greet", 2, 2)), expected to listOf(function("greet", 3, 3)))

        val (result, files) = runCrlf(file, texts, EditIn(path, null, old = "\n\nfun", new = "\n\n\nfun"))

        assertEquals(expected, files.content(path))
        assertIs<EditResult.Written>(result)
    }

    @Test
    fun `edit_in whose text occurs several times rejects the Edit and lists where`() = runTest {
        val (result, files) = run(emptyMap(), EditIn(path, null, old = "val n = 1", new = "val n = 2"))

        assertEquals(
            EditResult.OperationFailed(
                0,
                OperationFailure.NotExactlyOnce("val n = 1", EditInScope(null, path), 2, listOf(Occurrence(6, "val n = 1"), Occurrence(11, "val n = 1"))),
            ),
            result,
        )
        assertEquals(source, files.content(path))
    }

    @Test
    fun `edit_in whose text starts inside a line's indentation rejects the Edit, naming the line`() = runTest {
        // greet()'s body is indented 4 spaces; this 'old' starts after 2 of them.
        val (result, files) = run(emptyMap(), EditIn(path, "greet", old = "  val n = 1\n", new = "  val m = 1\n  val n = m\n"))

        assertEquals(EditResult.OperationFailed(0, OperationFailure.StartsInIndentation(EditInScope("greet", path), 6)), result)
        assertEquals(source, files.content(path))
    }

    @Test
    fun `edit_in on a whole file whose text starts inside a line's indentation rejects the Edit too`() = runTest {
        val (result, files) = run(emptyMap(), EditIn(path, null, old = "  return n\n}\n\nfun other", new = "  return 2\n}\n\nfun other"))

        assertEquals(EditResult.OperationFailed(0, OperationFailure.StartsInIndentation(EditInScope(null, path), 7)), result)
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

    @Test
    fun `edit_in on a whole file whose text starts at the beginning of a line, after the indentation or mid-line splices it as written`() = runTest {
        val expected = source
            .replace("import a.b.Unused", "import a.b.Used")
            .replaceFirst("    val n = 1\n", "    val n = 2\n")
            .replace("    val n = 1\n", "    val n = 3\n")
        val texts = mapOf(expected to symbols)

        val (result, files) = run(
            texts,
            EditIn(path, null, old = "    val n = 1\n    return n\n}\n\nfun other", new = "    val n = 2\n    return n\n}\n\nfun other"),
            EditIn(path, null, old = "val n = 1\n", new = "val n = 3\n"),
            EditIn(path, null, old = "Unused", new = "Used"),
        )

        assertEquals(expected, files.content(path))
        // The first text spans greet() and other(), so it lists neither; the second is inside other() alone.
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("other", 10, 13)), emptyList(), 0, 0))), result)
    }
}
