package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The imports an Operation lists: added to its file if missing, sorted in, and validated with the rest of the Edit. */
class EditImportsTest {
    private val root = Path.of("/project")
    private val path = "src/A.kt"

    private fun function(name: String, line: Int) = CodeSymbol(
        name,
        SymbolKind.FUNCTION,
        TextRange(Position(line, 0), Position(line, 20)),
        TextRange(Position(line, 4), Position(line, 4 + name.length)),
        emptyList(),
        "()",
    )

    /**
     * Runs [operations] on a file holding [source]; the fake reports the Symbols [symbolsByText] give for each exact
     * text, and organizes imports as [organizedByText] gives.
     */
    private suspend fun run(
        source: String,
        symbolsByText: Map<String, List<CodeSymbol>>,
        vararg operations: Operation,
        organizedByText: Map<String, String> = emptyMap(),
    ): Pair<EditResult, FakeFileStore> {
        val files = FakeFileStore(root, mapOf(path to source))
        val intelligence = FakeCodeIntelligence(symbolsByText = symbolsByText, organizedByText = organizedByText)
        val result = Edit(files, intelligence)(operations.toList())
        intelligence.assertOneSessionClosedEverything()
        return result to files
    }

    @Test
    fun `a file without imports gets a new import block after the package line`() = runTest {
        val source = "package a\n\nfun greet(): Int = 1\n"
        val expected = "package a\n\nimport kotlin.time.Instant\n\nfun greet(): Int = 1\n\nfun later(): Instant = TODO()\n"
        val texts = mapOf(source to listOf(function("greet", 2)), expected to listOf(function("greet", 4), function("later", 6)))

        val (result, files) = run(
            source,
            texts,
            InsertAfter(path, "greet", "fun later(): Instant = TODO()", imports = listOf("kotlin.time.Instant")),
        )

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("later", 7, 7)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `an import the file already has is left as it is`() = runTest {
        val source = "package a\n\nimport kotlin.time.Instant\n\nfun greet(): Int = 1\n"
        val expected = "package a\n\nimport kotlin.time.Instant\n\nfun greet(): Instant = TODO()\n"
        val texts = mapOf(source to listOf(function("greet", 4)), expected to listOf(function("greet", 4)))

        val (result, files) = run(
            source,
            texts,
            Replace(path, "greet", "fun greet(): Instant = TODO()", imports = listOf("import kotlin.time.Instant")),
        )

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 5, 5)), emptyList(), 0, 0))), result)
    }

    @Test
    fun `several Operations adding to the same file each add what is still missing, sorted in`() = runTest {
        val source = "package a\n\nimport c.Charlie\n\nfun greet(): Int = 1\n"
        val afterFirst = "package a\n\nimport b.Bravo\nimport c.Charlie\nimport d.Delta\n\nfun greet(): Int = 1\n\nfun later() = Bravo(Delta)\n"
        val expected = "package a\n\nimport a.Alpha\nimport b.Bravo\nimport c.Charlie\nimport d.Delta\n\nfun greet(): Int = Alpha.one\n\nfun later() = Bravo(Delta)\n"
        val texts = mapOf(
            source to listOf(function("greet", 4)),
            afterFirst to listOf(function("greet", 6), function("later", 8)),
            expected to listOf(function("greet", 7), function("later", 9)),
        )

        val (result, files) = run(
            source,
            texts,
            InsertAfter(path, "greet", "fun later() = Bravo(Delta)", imports = listOf("d.Delta", "b.Bravo", "c.Charlie")),
            EditIn(path, "greet", old = "= 1", new = "= Alpha.one", imports = listOf("a.Alpha", "b.Bravo")),
        )

        assertEquals(expected, files.content(path))
        assertEquals(
            EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 8, 8), EditedSymbol("later", 10, 10)), emptyList(), 0, 0))),
            result,
        )
    }

    @Test
    fun `a malformed import rejects the Edit before anything is applied`() = runTest {
        val source = "package a\n\nfun greet(): Int = 1\n"

        val (result, files) = run(
            source,
            mapOf(source to listOf(function("greet", 2))),
            Delete(path, "greet", imports = listOf("b.Bravo")),
            EditIn(path, null, old = "package a", new = "package b", imports = listOf("b.Bravo", "c Charlie")),
        )

        assertEquals(EditResult.OperationFailed(1, OperationFailure.InvalidImport("c Charlie")), result)
        assertEquals(source, files.content(path))
    }

    @Test
    fun `a delete that removes an import's last use removes the import too, and reports it`() = runTest {
        val source = "package a\n\nimport b.Bravo\nimport c.Charlie\n\nfun greet(): Int = Charlie.one\n\nfun make() = Bravo()\n"
        val expected = "package a\n\nimport c.Charlie\n\nfun greet(): Int = Charlie.one\n"
        val texts = mapOf(source to listOf(function("greet", 5), function("make", 7)), expected to listOf(function("greet", 4)))

        val afterDelete = "package a\n\nimport b.Bravo\nimport c.Charlie\n\nfun greet(): Int = Charlie.one\n"
        val organized = mapOf(source to source, afterDelete to expected)

        val (result, files) = run(source, texts, Delete(path, "make"), organizedByText = organized)

        assertEquals(expected, files.content(path))
        assertEquals(EditResult.Written(listOf(EditedFile(path, emptyList(), emptyList(), 0, 0, importsRemoved = listOf("b.Bravo")))), result)
    }

    @Test
    fun `moving a function to another file takes its import along, and an import an Operation lists stays even if unused`() = runTest {
        val other = "src/B.kt"
        val source = "package a\n\nimport b.B\nimport c.C\n\nfun greet(): Int = 1\n\nfun make() = B(C)\n"
        val target = "package a\n\nfun hello(): Int = 2\n"
        val sourceAfter = "package a\n\nimport c.C\n\nfun greet(): Int = 1\n"
        val targetAfter = "package a\n\nimport b.B\n\nfun hello(): Int = 2\n\nfun make() = B(1)\n"
        val files = FakeFileStore(root, mapOf(path to source, other to target))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                source to listOf(function("greet", 5), function("make", 7)),
                target to listOf(function("hello", 2)),
                sourceAfter to listOf(function("greet", 4)),
                targetAfter to listOf(function("hello", 4), function("make", 6)),
            ),
            organizedByText = mapOf(
                source to source,
                "package a\n\nimport b.B\nimport c.C\n\nfun greet(): Int = 1\n" to "package a\n\nfun greet(): Int = 1\n",
            ),
        )

        val result = Edit(files, intelligence)(
            listOf(
                Delete(path, "make", imports = listOf("c.C")),
                InsertAfter(other, "hello", "fun make() = B(1)", imports = listOf("b.B")),
            ),
        )

        intelligence.assertOneSessionClosedEverything()
        assertEquals(
            EditResult.Written(
                listOf(
                    EditedFile(path, emptyList(), emptyList(), 0, 0, importsRemoved = listOf("b.B")),
                    EditedFile(other, listOf(EditedSymbol("make", 7, 7)), emptyList(), 0, 0),
                ),
            ),
            result,
        )
        assertEquals(sourceAfter, files.content(path))
        assertEquals(targetAfter, files.content(other))
    }

    @Test
    fun `a replace removes an import it made unused, keeps one that was already unused, and reorders nothing`() = runTest {
        val source = "package a\n\nimport d.D\nimport b.B\nimport c.Old\n\nfun greet() = D + B\n"
        val replaced = "package a\n\nimport d.D\nimport b.B\nimport c.Old\n\nfun greet() = D\n"
        val expected = "package a\n\nimport d.D\nimport c.Old\n\nfun greet() = D\n"
        val texts = mapOf(source to listOf(function("greet", 6)), expected to listOf(function("greet", 5)))
        // Organize-imports sorts, and drops c.Old both before and after.
        val organized = mapOf(
            source to "package a\n\nimport b.B\nimport d.D\n\nfun greet() = D + B\n",
            replaced to "package a\n\nimport d.D\n\nfun greet() = D\n",
        )

        val (result, files) = run(source, texts, Replace(path, "greet", "fun greet() = D"), organizedByText = organized)

        assertEquals(EditResult.Written(listOf(EditedFile(path, listOf(EditedSymbol("greet", 6, 6)), emptyList(), 0, 0, importsRemoved = listOf("b.B")))), result)
        assertEquals(expected, files.content(path))
    }

    @Test
    fun `without an organize-imports answer, every import stays`() = runTest {
        val source = "package a\n\nimport b.Bravo\n\nfun greet(): Int = 1\n\nfun make() = Bravo()\n"
        val expected = "package a\n\nimport b.Bravo\n\nfun greet(): Int = 1\n"
        val texts = mapOf(source to listOf(function("greet", 4), function("make", 6)), expected to listOf(function("greet", 4)))

        val (result, files) = run(source, texts, Delete(path, "make"))

        assertEquals(EditResult.Written(listOf(EditedFile(path, emptyList(), emptyList(), 0, 0))), result)
        assertEquals(expected, files.content(path))
    }
}
