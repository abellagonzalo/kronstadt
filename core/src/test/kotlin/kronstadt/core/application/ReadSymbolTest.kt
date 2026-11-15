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

class ReadSymbolTest {
    private val root = Path.of("/project")

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    /** A symbol whose name sits at [nameLine]:[nameChar], as kotlin-lsp reports it. */
    private fun symbol(
        name: String,
        kind: SymbolKind,
        range: TextRange,
        nameLine: Int,
        nameChar: Int,
        detail: String? = null,
        children: List<CodeSymbol> = emptyList(),
    ) = CodeSymbol(name, kind, range, range(nameLine, nameChar, nameLine, nameChar + name.length), children, detail)

    private fun readSymbol(source: String, vararg symbols: CodeSymbol, intelligence: FakeCodeIntelligence = FakeCodeIntelligence(symbols.toList())) =
        ReadSymbol(FakeFileStore(root, mapOf("src/A.kt" to source)), intelligence)

    @Test
    fun `reads a top-level function as a header and its raw source`() = runTest {
        val source = "package a\n\nfun greet(): String =\n    \"hi\"\n"
        val greet = symbol("greet", SymbolKind.FUNCTION, range(2, 0, 3, 8), 2, 4, "(): String")

        val result = assertIs<ReadSymbolResult.Found>(readSymbol(source, greet)("src/A.kt", "greet"))

        assertEquals("// greet in src/A.kt, L3-4\nfun greet(): String =\n    \"hi\"", result.text)
    }

    // Nested declarations, as kotlin-lsp reports them: a class range starts at its KDoc and covers annotations.
    private val nested = """
        |package a
        |
        |/** Runs things. */
        |@Suppress("unused")
        |class Runner {
        |    fun run(): Int = 1
        |
        |    class Nested {
        |        fun run(): Int = 2 // the nested one
        |    }
        |}
        |
        |fun run(): Int = 3
        |""".trimMargin()
    private val outerRun = symbol("run", SymbolKind.FUNCTION, range(5, 4, 5, 22), 5, 8, "(): Int")
    private val nestedRun = symbol("run", SymbolKind.FUNCTION, range(8, 8, 8, 45), 8, 12, "(): Int")
    private val nestedClass = symbol("Nested", SymbolKind.CLASS, range(7, 4, 9, 5), 7, 10, children = listOf(nestedRun))
    private val runner = symbol("Runner", SymbolKind.CLASS, range(2, 0, 10, 1), 4, 6, children = listOf(outerRun, nestedClass))
    private val topRun = symbol("run", SymbolKind.FUNCTION, range(12, 0, 12, 18), 12, 4, "(): Int")

    private suspend fun read(symbol: String) = readSymbol(nested, runner, topRun)("src/A.kt", symbol)

    @Test
    fun `reading a class gives the whole class with its KDoc, annotations and nested members`() = runTest {
        val result = assertIs<ReadSymbolResult.Found>(read("Runner"))
        assertEquals("// Runner in src/A.kt, L3-11\n" + nested.lines().subList(2, 11).joinToString("\n"), result.text)
    }

    @Test
    fun `trailing segments address a nested Symbol, and the end-of-line comment belongs to it`() = runTest {
        val result = assertIs<ReadSymbolResult.Found>(read("Nested/run"))
        assertEquals("// Nested/run in src/A.kt, L9-9\n        fun run(): Int = 2 // the nested one", result.text)
        assertIs<ReadSymbolResult.Found>(read("Runner/Nested/run"))
    }

    @Test
    fun `a leading slash anchors the path at the top level`() = runTest {
        val result = assertIs<ReadSymbolResult.Found>(read("/run"))
        assertEquals("// /run in src/A.kt, L13-13\nfun run(): Int = 3", result.text)
        assertIs<ReadSymbolResult.NotFound>(read("/Nested"))
    }

    @Test
    fun `a name shared by several Symbols fails and lists every candidate's canonical path`() = runTest {
        val result = assertIs<ReadSymbolResult.Ambiguous>(read("run"))
        assertEquals("src/A.kt", result.file)
        assertEquals(listOf("Runner/run", "Nested/run", "/run"), result.candidates)
    }

    @Test
    fun `matching is exact and case-sensitive, and a miss suggests close names`() = runTest {
        assertEquals(listOf("Runner"), assertIs<ReadSymbolResult.NotFound>(read("runner")).suggestions)
        assertEquals(listOf("Runner"), assertIs<ReadSymbolResult.NotFound>(read("Runer")).suggestions)
        assertEquals(emptyList(), assertIs<ReadSymbolResult.NotFound>(read("Completely/Different")).suggestions)
    }

    private val overloads = """
        |class Parser {
        |    fun parse(text: String): Int = text.toInt()
        |
        |    fun parse(text: String, radix: Int): Int = text.toInt(radix)
        |
        |    fun merge(a: Map<String, Int>, b: List<Int>): Int = 0
        |}
        |""".trimMargin()
    private val parse1 = symbol("parse", SymbolKind.FUNCTION, range(1, 4, 1, 47), 1, 8, "(String): Int")
    private val parse2 = symbol("parse", SymbolKind.FUNCTION, range(3, 4, 3, 64), 3, 8, "(String, Int): Int")
    private val merge = symbol("merge", SymbolKind.FUNCTION, range(5, 4, 5, 57), 5, 8, "(Map<String, Int>, List<Int>): Int")
    private val parser = symbol("Parser", SymbolKind.CLASS, range(0, 0, 6, 1), 0, 6, children = listOf(parse1, parse2, merge))

    private suspend fun readOverload(symbol: String) = readSymbol(overloads, parser)("src/A.kt", symbol)

    @Test
    fun `a parameter-type signature picks an overload, ignoring whitespace`() = runTest {
        val result = assertIs<ReadSymbolResult.Found>(readOverload("parse( String,Int )"))
        assertEquals("// parse(String, Int) in src/A.kt, L4-4\n    fun parse(text: String, radix: Int): Int = text.toInt(radix)", result.text)
        assertIs<ReadSymbolResult.Found>(readOverload("Parser/merge(Map<String,Int>, List<Int>)"))
        assertEquals(listOf("parse(String)", "parse(String, Int)"), assertIs<ReadSymbolResult.Ambiguous>(readOverload("parse")).candidates)
    }

    @Test
    fun `a signature is compared as text, without resolving types`() = runTest {
        assertIs<ReadSymbolResult.NotFound>(readOverload("parse(kotlin.String)"))
        assertIs<ReadSymbolResult.NotFound>(readOverload("Parser(String)"))
    }

    @Test
    fun `the canonical path is the shortest unambiguous one`() = runTest {
        assertEquals("// merge in src/A.kt, L6-6", assertIs<ReadSymbolResult.Found>(readOverload("/Parser/merge")).text.lines().first())
    }

    @Test
    fun `a malformed path is rejected before anything is read`() = runTest {
        val intelligence = FakeCodeIntelligence(listOf(parser))
        listOf("", "Parser//parse", "Parser/", "parse(String", "parse(String)x").forEach { path ->
            assertIs<ReadSymbolResult.InvalidPath>(readSymbol(overloads, intelligence = intelligence)("src/A.kt", path), path)
        }
        assertEquals(emptyList(), intelligence.analysed)
    }

    @Test
    fun `refuses with out of sync when the name is not where the analysis says`() = runTest {
        val stale = symbol("parse", SymbolKind.FUNCTION, range(1, 4, 1, 47), 1, 12, "(String): Int")
        assertIs<ReadSymbolResult.OutOfSync>(readSymbol(overloads, stale)("src/A.kt", "parse"))
    }

    @Test
    fun `refuses a Symbol that shares its first or last line with other code`() = runTest {
        val source = "class Box(val width: Int) { val area = 1 }; val x = 2\n"
        val width = symbol("width", SymbolKind.PROPERTY, range(0, 10, 0, 24), 0, 14)
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 0, 42), 0, 6, children = listOf(width))
        val read = readSymbol(source, box)
        assertEquals(1, assertIs<ReadSymbolResult.SharedLine>(read("src/A.kt", "Box/width")).line)
        assertIs<ReadSymbolResult.SharedLine>(read("src/A.kt", "Box"))
    }

    @Test
    fun `code between two block comments after the Symbol still counts as sharing its line`() = runTest {
        val source = "fun a() = 1 /* x */ ; val b = 2 /* y */\n"
        val a = symbol("a", SymbolKind.FUNCTION, range(0, 0, 0, 11), 0, 4, "(): Int")
        assertIs<ReadSymbolResult.SharedLine>(readSymbol(source, a)("src/A.kt", "a"))
    }

    @Test
    fun `columns count UTF-16 units, so names after surrogate pairs are found`() = runTest {
        val source = "/** 😀 */ fun `😀name`(): Int =\n    1\n"
        // "/** 😀 */ " is 10 UTF-16 units, "fun " 4 more: the backticked name starts at column 14.
        val fn = CodeSymbol("😀name", SymbolKind.FUNCTION, range(0, 0, 1, 5), range(0, 14, 0, 22), detail = "(): Int")
        val result = assertIs<ReadSymbolResult.Found>(readSymbol(source, fn)("src/A.kt", "`😀name`"))
        assertEquals("// `😀name` in src/A.kt, L1-2\n/** 😀 */ fun `😀name`(): Int =\n    1", result.text)
    }

    @Test
    fun `closes the document it analysed, whatever the outcome`() = runTest {
        listOf("Runner", "run", "Nope").forEach { path ->
            val intelligence = FakeCodeIntelligence(listOf(runner, topRun))
            readSymbol(nested, intelligence = intelligence)("src/A.kt", path)
            assertEquals(1, intelligence.analysed.size)
            assertEquals(emptySet(), intelligence.openDocuments, path)
        }
    }

    @Test
    fun `answers while the index is still being built, without waiting`() = runTest {
        val intelligence = FakeCodeIntelligence(listOf(runner, topRun), notReadyPhase = "Indexing")
        assertIs<ReadSymbolResult.Found>(readSymbol(nested, intelligence = intelligence)("src/A.kt", "Runner"))
    }

    @Test
    fun `reports missing files, non-Kotlin files, and paths escaping the project`() = runTest {
        val read = readSymbol(nested, runner)
        assertIs<ReadSymbolResult.FileNotFound>(read("src/Missing.kt", "Runner"))
        assertIs<ReadSymbolResult.NotKotlin>(read("README.md", "Runner"))
        assertIs<ReadSymbolResult.OutsideProject>(read("../other/B.kt", "Runner"))
    }

    @Test
    fun `a class is not confused with its constructors, which kotlin-lsp names after the class`() = runTest {
        val source = "class Box(val width: Int) {\n    constructor() : this(0)\n}\n"
        // kotlin-lsp gives constructors no separate name range: selectionRange is the whole declaration.
        val primary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(0, 9, 0, 25), range(0, 9, 0, 25), detail = "(Int)")
        val secondary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(1, 4, 1, 27), range(1, 4, 1, 27), detail = "()")
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 2, 1), 0, 6, children = listOf(primary, secondary))
        val read = readSymbol(source, box)

        assertEquals("// Box in src/A.kt, L1-3", assertIs<ReadSymbolResult.Found>(read("src/A.kt", "Box")).text.lines().first())
        val result = assertIs<ReadSymbolResult.Found>(read("src/A.kt", "Box/constructor()"))
        assertEquals("// constructor() in src/A.kt, L2-2\n    constructor() : this(0)", result.text)
    }
}
