package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** `insert_before`, `insert_after` and `delete` Operations of an Edit. */
class InsertAndDeleteTest {
    private val root = Path.of("/project")
    private val path = "src/A.kt"

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    /** A function declared on one line, `fun <name>(): Int = <n>`, at [line] and [indent], plus [extraLinesAbove] of KDoc. */
    private fun function(name: String, line: Int, indent: Int, extraLinesAbove: Int = 0, n: Int = 1) = CodeSymbol(
        name,
        SymbolKind.FUNCTION,
        range(line - extraLinesAbove, indent, line, indent + "fun $name(): Int = $n".length),
        range(line, indent + 4, line, indent + 4 + name.length),
        emptyList(),
        parameterTypes = "",
        type = "(): Int",
    )

    private fun greeter(firstLine: Int, lastLine: Int, vararg members: CodeSymbol) =
        CodeSymbol("Greeter", SymbolKind.CLASS, range(firstLine, 0, lastLine, 1), range(firstLine, 6, firstLine, 13), members.toList(), null)

    private val source = """
        |package a
        |
        |class Greeter {
        |    /** Says hi. */
        |    fun greet(): Int = 1
        |
        |    fun other(): Int = 1
        |}
        |""".trimMargin()
    private val greet = function("greet", 4, 4, extraLinesAbove = 1)
    private val other = function("other", 6, 4)

    /** Runs [operations] on a file holding the first of [texts], whose Symbols the fake reports for each text. */
    private suspend fun run(texts: Map<String, List<CodeSymbol>>, vararg operations: Operation): Pair<EditResult, FakeFileStore> {
        val files = FakeFileStore(root, mapOf(path to texts.keys.first()))
        val intelligence = FakeCodeIntelligence(symbolsByText = texts)
        val result = Edit(files, intelligence)(operations.toList())
        intelligence.assertOneSessionClosedEverything()
        return result to files
    }

    @Test
    fun `insert_after adds a sibling at the target's indentation, re-indented, one blank line below it`() = runTest {
        val expected = """
            |package a
            |
            |class Greeter {
            |    /** Says hi. */
            |    fun greet(): Int = 1
            |
            |    fun other(): Int = 1
            |
            |    fun added(): Int = 2
            |}
            |""".trimMargin()
        val texts = mapOf(
            source to listOf(greeter(2, 7, greet, other)),
            expected to listOf(greeter(2, 9, greet, other, function("added", 8, 4, n = 2))),
        )

        val (result, files) = run(texts, InsertAfter(path, "Greeter/other", "fun added(): Int = 2\n"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("added", 9, 9)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `insert_before goes above the target's KDoc and annotations, one blank line above it`() = runTest {
        val expected = """
            |package a
            |
            |class Greeter {
            |    fun added(): Int = 2
            |
            |    /** Says hi. */
            |    fun greet(): Int = 1
            |
            |    fun other(): Int = 1
            |}
            |""".trimMargin()
        val texts = mapOf(
            source to listOf(greeter(2, 7, greet, other)),
            expected to listOf(greeter(2, 9, function("added", 3, 4, n = 2), function("greet", 6, 4, extraLinesAbove = 1), function("other", 8, 4))),
        )

        val (result, files) = run(texts, InsertBefore(path, "Greeter/greet", "\n  fun added(): Int = 2\n\n"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("added", 4, 4)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `insert_before and insert_after with no non-blank line of code fail with the Operation's index, writing nothing`() = runTest {
        val texts = mapOf(source to listOf(greeter(2, 7, greet, other)))

        for (code in listOf("", "\n", "  \n\t\n")) {
            val (before, beforeFiles) = run(texts, InsertBefore(path, "Greeter/greet", code))
            val (after, afterFiles) = run(texts, Delete(path, "Greeter/greet"), InsertAfter(path, "Greeter/other", code))

            assertEquals(EditResult.OperationFailed(0, OperationFailure.BlankCode), before)
            assertEquals(EditResult.OperationFailed(1, OperationFailure.BlankCode), after)
            assertEquals(source, beforeFiles.content(path))
            assertEquals(source, afterFiles.content(path))
        }
    }

    @Test
    fun `delete removes the whole span and the blank line below it, or above it when there is none below`() = runTest {
        val withoutGreet = """
            |package a
            |
            |class Greeter {
            |    fun other(): Int = 1
            |}
            |""".trimMargin()
        val withoutOther = """
            |package a
            |
            |class Greeter {
            |    /** Says hi. */
            |    fun greet(): Int = 1
            |}
            |""".trimMargin()
        val texts = mapOf(
            source to listOf(greeter(2, 7, greet, other)),
            withoutGreet to listOf(greeter(2, 4, function("other", 3, 4))),
            withoutOther to listOf(greeter(2, 5, greet)),
        )

        val (greetResult, greetFiles) = run(texts, Delete(path, "Greeter/greet"))
        val (otherResult, otherFiles) = run(texts, Delete(path, "Greeter/other"))

        assertEquals(withoutGreet, greetFiles.content(path))
        assertEquals(withoutOther, otherFiles.content(path))
        val nothingLeft = EditResult.Written(listOf(EditedFile(path, emptyList(), emptyList(), 0, 0)))
        assertEquals(nothingLeft, greetResult)
        assertEquals(nothingLeft, otherResult)
    }

    @Test
    fun `inserted code nested two levels deep in a file indented with tabs is re-indented with tabs`() = runTest {
        val tabs = "package a\n\nclass Outer {\n\tclass Inner {\n\t\tfun one(): Int = 1\n\t}\n}\n"
        val expected = "package a\n\nclass Outer {\n\tclass Inner {\n\t\tfun one(): Int = 1\n\n\t\tfun two(): Int {\n\t\t\treturn 2\n\t\t}\n\t}\n}\n"
        fun outer(lastLine: Int, vararg members: CodeSymbol) =
            CodeSymbol("Outer", SymbolKind.CLASS, range(2, 0, lastLine, 1), range(2, 6, 2, 11), listOf(CodeSymbol("Inner", SymbolKind.CLASS, range(3, 1, lastLine - 1, 2), range(3, 7, 3, 12), members.toList(), null)), null)
        val one = function("one", 4, 2)
        val two = CodeSymbol("two", SymbolKind.FUNCTION, range(6, 2, 8, 3), range(6, 6, 6, 9), emptyList(), parameterTypes = "", type = "(): Int")
        val texts = mapOf(tabs to listOf(outer(6, one)), expected to listOf(outer(10, one, two)))

        val (result, files) = run(texts, InsertAfter(path, "Inner/one", "    fun two(): Int {\n        return 2\n    }"))

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("two", 7, 9)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `inserts and deletes mix with replaces in one batch, a later Operation targeting what an insert added`() = runTest {
        fun withAdded(n: Int) = """
            |package a
            |
            |class Greeter {
            |    fun added(): Int = $n
            |
            |    /** Says hi. */
            |    fun greet(): Int = 1
            |
            |    fun other(): Int = 1
            |}
            |""".trimMargin()
        val expected = """
            |package a
            |
            |class Greeter {
            |    fun added(): Int = 3
            |
            |    /** Says hi. */
            |    fun greet(): Int = 1
            |}
            |""".trimMargin()
        val texts = mapOf(
            source to listOf(greeter(2, 7, greet, other)),
            withAdded(2) to listOf(greeter(2, 9, function("added", 3, 4, n = 2), function("greet", 6, 4, extraLinesAbove = 1), function("other", 8, 4))),
            withAdded(3) to listOf(greeter(2, 9, function("added", 3, 4, n = 3), function("greet", 6, 4, extraLinesAbove = 1), function("other", 8, 4))),
            expected to listOf(greeter(2, 7, function("added", 3, 4, n = 3), function("greet", 6, 4, extraLinesAbove = 1))),
        )

        val (result, files) = run(
            texts,
            InsertBefore(path, "Greeter/greet", "fun added(): Int = 2"),
            Replace(path, "Greeter/added", "fun added(): Int = 3"),
            Delete(path, "Greeter/other"),
        )

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("added", 4, 4)), emptyList(), 0, 0))), result)
    }
}
