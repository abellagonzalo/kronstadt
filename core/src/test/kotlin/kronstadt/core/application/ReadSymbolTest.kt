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
        parameterTypes: String? = null, type: String? = null, receiver: String? = null,
        children: List<CodeSymbol> = emptyList(),
    ) = CodeSymbol(name, kind, range, range(nameLine, nameChar, nameLine, nameChar + name.length), children, parameterTypes = parameterTypes, type = type, receiver = receiver)

    private fun readSymbol(source: String, vararg symbols: CodeSymbol, intelligence: FakeCodeIntelligence = FakeCodeIntelligence(symbols.toList())) =
        ReadSymbol(FakeFileStore(root, mapOf("src/A.kt" to source)), intelligence)

    @Test
    fun `reads a top-level function as a header and its raw source`() = runTest {
        val source = "package a\n\nfun greet(): String =\n    \"hi\"\n"
        val greet = symbol("greet", SymbolKind.FUNCTION, range(2, 0, 3, 8), 2, 4, parameterTypes = "", type = "(): String")

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
    private val outerRun = symbol("run", SymbolKind.FUNCTION, range(5, 4, 5, 22), 5, 8, parameterTypes = "", type = "(): Int")
    private val nestedRun = symbol("run", SymbolKind.FUNCTION, range(8, 8, 8, 45), 8, 12, parameterTypes = "", type = "(): Int")
    private val nestedClass = symbol("Nested", SymbolKind.CLASS, range(7, 4, 9, 5), 7, 10, children = listOf(nestedRun))
    private val runner = symbol("Runner", SymbolKind.CLASS, range(2, 0, 10, 1), 4, 6, children = listOf(outerRun, nestedClass))
    private val topRun = symbol("run", SymbolKind.FUNCTION, range(12, 0, 12, 18), 12, 4, parameterTypes = "", type = "(): Int")

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
        assertIs<LookupFailure.NotFound>(read("/Nested").lookupFailure())
    }

    @Test
    fun `a name shared by several Symbols fails and lists every candidate's canonical path`() = runTest {
        val result = assertIs<LookupFailure.Ambiguous>(read("run").lookupFailure())
        assertEquals("src/A.kt", result.file)
        assertEquals(listOf("Runner/run", "Nested/run", "/run"), result.candidates.map { it.path })
    }

    @Test
    fun `matching is exact and case-sensitive, and a miss suggests close names`() = runTest {
        assertEquals(listOf("Runner"), assertIs<LookupFailure.NotFound>(read("runner").lookupFailure()).suggestions.map { it.path })
        assertEquals(listOf("Runner"), assertIs<LookupFailure.NotFound>(read("Runer").lookupFailure()).suggestions.map { it.path })
        assertEquals(emptyList(), assertIs<LookupFailure.NotFound>(read("Completely/Different").lookupFailure()).suggestions.map { it.path })
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
    private val parse1 = symbol("parse", SymbolKind.FUNCTION, range(1, 4, 1, 47), 1, 8, parameterTypes = "String", type = "(String): Int")
    private val parse2 = symbol("parse", SymbolKind.FUNCTION, range(3, 4, 3, 64), 3, 8, parameterTypes = "String, Int", type = "(String, Int): Int")
    private val merge = symbol("merge", SymbolKind.FUNCTION, range(5, 4, 5, 57), 5, 8, parameterTypes = "Map<String, Int>, List<Int>", type = "(Map<String, Int>, List<Int>): Int")
    private val parser = symbol("Parser", SymbolKind.CLASS, range(0, 0, 6, 1), 0, 6, children = listOf(parse1, parse2, merge))

    private suspend fun readOverload(symbol: String) = readSymbol(overloads, parser)("src/A.kt", symbol)

    @Test
    fun `a parameter-type signature picks an overload, ignoring whitespace`() = runTest {
        val result = assertIs<ReadSymbolResult.Found>(readOverload("parse( String,Int )"))
        assertEquals("// parse(String, Int) in src/A.kt, L4-4\n    fun parse(text: String, radix: Int): Int = text.toInt(radix)", result.text)
        assertIs<ReadSymbolResult.Found>(readOverload("Parser/merge(Map<String,Int>, List<Int>)"))
        assertEquals(listOf("parse(String)", "parse(String, Int)"), assertIs<LookupFailure.Ambiguous>(readOverload("parse").lookupFailure()).candidates.map { it.path })
    }

    @Test
    fun `a signature is compared as text, without resolving types`() = runTest {
        assertIs<LookupFailure.NotFound>(readOverload("parse(kotlin.String)").lookupFailure())
        assertIs<LookupFailure.NotFound>(readOverload("Parser(String)").lookupFailure())
    }

    @Test
    fun `the canonical path is the shortest unambiguous one`() = runTest {
        assertEquals("// merge in src/A.kt, L6-6", assertIs<ReadSymbolResult.Found>(readOverload("/Parser/merge")).text.lines().first())
    }

    @Test
    fun `a malformed path is rejected before anything is read`() = runTest {
        val intelligence = FakeCodeIntelligence(listOf(parser))
        listOf("", "Parser//parse", "Parser/", "parse(String", "parse(String)x").forEach { path ->
            assertIs<LookupFailure.InvalidPath>(readSymbol(overloads, intelligence = intelligence)("src/A.kt", path).lookupFailure(), path)
        }
        assertEquals(emptyList(), intelligence.analysed)
    }

    @Test
    fun `refuses with out of sync when the name is not where the analysis says`() = runTest {
        val stale = symbol("parse", SymbolKind.FUNCTION, range(1, 4, 1, 47), 1, 12, parameterTypes = "String", type = "(String): Int")
        assertIs<LookupFailure.OutOfSync>(readSymbol(overloads, stale)("src/A.kt", "parse").lookupFailure())
    }

    @Test
    fun `refuses a Symbol that shares its first or last line with other code`() = runTest {
        val source = "class Box(val width: Int) { val area = 1 }; val x = 2\n"
        val width = symbol("width", SymbolKind.PROPERTY, range(0, 10, 0, 24), 0, 14)
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 0, 42), 0, 6, children = listOf(width))
        val read = readSymbol(source, box)
        assertEquals(1, assertIs<LookupFailure.SharedLine>(read("src/A.kt", "Box/width").lookupFailure()).line)
        assertIs<LookupFailure.SharedLine>(read("src/A.kt", "Box").lookupFailure())
    }

    // A multi-line, trailing-comma constructor: kotlin-lsp's range for each property ends before its `,`.
    private val trailingCommas = """
        |class Box(
        |    val width: Int,
        |    val height: Int, // tall
        |)
        |""".trimMargin()
    private val commaWidth = symbol("width", SymbolKind.VARIABLE, range(1, 4, 1, 18), 1, 8, type = ": Int")
    private val commaHeight = symbol("height", SymbolKind.VARIABLE, range(2, 4, 2, 19), 2, 8, type = ": Int")
    private val commaBox = symbol("Box", SymbolKind.CLASS, range(0, 0, 3, 1), 0, 6, children = listOf(commaWidth, commaHeight))

    @Test
    fun `a trailing comma after the Symbol belongs to its span, with or without an end-of-line comment`() = runTest {
        val read = readSymbol(trailingCommas, commaBox)
        assertEquals("// width in src/A.kt, L2-2\n    val width: Int,", assertIs<ReadSymbolResult.Found>(read("src/A.kt", "width")).text)
        assertEquals("// height in src/A.kt, L3-3\n    val height: Int, // tall", assertIs<ReadSymbolResult.Found>(read("src/A.kt", "height")).text)
    }

    @Test
    fun `a comma followed by more code, or a trailing semicolon, still counts as sharing the line`() = runTest {
        val source = "class Box(\n    val a: Int, val b: Int,\n)\nval c = 1;\n"
        val a = symbol("a", SymbolKind.PROPERTY, range(1, 4, 1, 14), 1, 8)
        val b = symbol("b", SymbolKind.PROPERTY, range(1, 16, 1, 26), 1, 20)
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 2, 1), 0, 6, children = listOf(a, b))
        val c = symbol("c", SymbolKind.PROPERTY, range(3, 0, 3, 9), 3, 4)
        val read = readSymbol(source, box, c)
        assertEquals(2, assertIs<LookupFailure.SharedLine>(read("src/A.kt", "a").lookupFailure()).line)
        assertEquals(4, assertIs<LookupFailure.SharedLine>(read("src/A.kt", "c").lookupFailure()).line)
    }

    @Test
    fun `code between two block comments after the Symbol still counts as sharing its line`() = runTest {
        val source = "fun a() = 1 /* x */ ; val b = 2 /* y */\n"
        val a = symbol("a", SymbolKind.FUNCTION, range(0, 0, 0, 11), 0, 4, parameterTypes = "", type = "(): Int")
        assertIs<LookupFailure.SharedLine>(readSymbol(source, a)("src/A.kt", "a").lookupFailure())
    }

    @Test
    fun `columns count UTF-16 units, so names after surrogate pairs are found`() = runTest {
        val source = "/** 😀 */ fun `😀name`(): Int =\n    1\n"
        // "/** 😀 */ " is 10 UTF-16 units, "fun " 4 more: the backticked name starts at column 14.
        val fn = CodeSymbol("😀name", SymbolKind.FUNCTION, range(0, 0, 1, 5), range(0, 14, 0, 22), parameterTypes = "", type = "(): Int")
        val result = assertIs<ReadSymbolResult.Found>(readSymbol(source, fn)("src/A.kt", "`😀name`"))
        assertEquals("// `😀name` in src/A.kt, L1-2\n/** 😀 */ fun `😀name`(): Int =\n    1", result.text)
    }

    @Test
    fun `closes the document it analysed, whatever the outcome`() = runTest {
        listOf("Runner", "run", "Nope").forEach { path ->
            val intelligence = FakeCodeIntelligence(listOf(runner, topRun))
            readSymbol(nested, intelligence = intelligence)("src/A.kt", path)
            assertEquals(1, intelligence.analysed.size)
            intelligence.assertOneSessionClosedEverything(path)
        }
    }

    @Test
    fun `answers while the index is still being built, without waiting`() = runTest {
        val intelligence = FakeCodeIntelligence(listOf(runner, topRun), notReadyPhase = "Indexing")
        assertIs<ReadSymbolResult.Found>(readSymbol(nested, intelligence = intelligence)("src/A.kt", "Runner"))
        assertEquals(0, intelligence.readinessAwaits)
    }

    @Test
    fun `a file the lookup module rejects is reported as its lookup failure`() = runTest {
        assertEquals(ReadSymbolResult.LookupFailed(LookupFailure.NotKotlin("README.md")), readSymbol(nested, runner)("README.md", "Runner"))
    }

    @Test
    fun `a class is not confused with its constructors, which kotlin-lsp names after the class`() = runTest {
        val source = "class Box(val width: Int) {\n    constructor() : this(0)\n}\n"
        // kotlin-lsp gives constructors no separate name range: selectionRange is the whole declaration.
        val primary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(0, 9, 0, 25), range(0, 9, 0, 25), parameterTypes = "Int", type = "(Int)")
        val secondary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(1, 4, 1, 27), range(1, 4, 1, 27), parameterTypes = "", type = "()")
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 2, 1), 0, 6, children = listOf(primary, secondary))
        val read = readSymbol(source, box)

        assertEquals("// Box in src/A.kt, L1-3", assertIs<ReadSymbolResult.Found>(read("src/A.kt", "Box")).text.lines().first())
        val result = assertIs<ReadSymbolResult.Found>(read("src/A.kt", "Box/constructor()"))
        assertEquals("// constructor() in src/A.kt, L2-2\n    constructor() : this(0)", result.text)
    }

    // Special forms (ADR 0002), with trees shaped as kotlin-lsp 271.2085.0 reports them (see the contract test).

    private val extensions = """
        |class Formatter {
        |    fun String.decorate(): String = "*${'$'}this*"
        |
        |    fun Int.decorate(): String = "#${'$'}this"
        |
        |    fun decorate(): String = "plain"
        |}
        |
        |fun String.shout(): String = uppercase()
        |
        |fun <T> List<T>.second(): T = this[1]
        |
        |val String.firstChar: Char
        |    get() = this[0]
        |""".trimMargin()
    private val stringDecorate = symbol("decorate", SymbolKind.FUNCTION, range(1, 4, 1, 45), 1, 15, parameterTypes = "", type = "(): String", receiver = "String")
    private val intDecorate = symbol("decorate", SymbolKind.FUNCTION, range(3, 4, 3, 41), 3, 12, parameterTypes = "", type = "(): String", receiver = "Int")
    private val plainDecorate = symbol("decorate", SymbolKind.FUNCTION, range(5, 4, 5, 36), 5, 8, parameterTypes = "", type = "(): String")
    private val formatter = symbol("Formatter", SymbolKind.CLASS, range(0, 0, 6, 1), 0, 6, children = listOf(stringDecorate, intDecorate, plainDecorate))
    private val shout = symbol("shout", SymbolKind.FUNCTION, range(8, 0, 8, 41), 8, 11, parameterTypes = "", type = "(): String", receiver = "String")
    private val second = symbol("second", SymbolKind.FUNCTION, range(10, 0, 10, 37), 10, 16, parameterTypes = "", type = "(): T", receiver = "List<T>")
    private val firstCharGetter = CodeSymbol("get", SymbolKind.FUNCTION, range(13, 4, 13, 19), range(13, 4, 13, 19))
    private val firstChar = symbol("firstChar", SymbolKind.PROPERTY, range(12, 0, 13, 19), 12, 11, type = ": Char", receiver = "String", children = listOf(firstCharGetter))

    private suspend fun readExtension(symbol: String) =
        readSymbol(extensions, formatter, shout, second, firstChar)("src/A.kt", symbol)

    @Test
    fun `an extension receiver is written inside the segment, Kotlin-style`() = runTest {
        assertEquals("// String.shout in src/A.kt, L9-9\nfun String.shout(): String = uppercase()", assertIs<ReadSymbolResult.Found>(readExtension("String.shout")).text)
        assertEquals("// String.decorate in src/A.kt, L2-2", assertIs<ReadSymbolResult.Found>(readExtension("Formatter/String.decorate")).text.lines().first())
        assertEquals("// Int.decorate in src/A.kt, L4-4", assertIs<ReadSymbolResult.Found>(readExtension("Int.decorate")).text.lines().first())
        assertEquals("// decorate in src/A.kt, L6-6", assertIs<ReadSymbolResult.Found>(readExtension("Formatter/decorate")).text.lines().first())
        assertEquals("// List<T>.second in src/A.kt, L11-11", assertIs<ReadSymbolResult.Found>(readExtension("List< T >.second")).text.lines().first())
        assertEquals("// String.firstChar in src/A.kt, L13-14", assertIs<ReadSymbolResult.Found>(readExtension("String.firstChar")).text.lines().first())
    }

    @Test
    fun `the receiver is part of the name, so leaving it out misses and suggests the extension`() = runTest {
        assertEquals(listOf("String.shout"), assertIs<LookupFailure.NotFound>(readExtension("shout").lookupFailure()).suggestions.map { it.path })
        assertIs<LookupFailure.NotFound>(readExtension("Int.shout").lookupFailure())
        assertIs<LookupFailure.NotFound>(readExtension("CharSequence.firstChar").lookupFailure())
    }

    private val sized = """
        |class Sized {
        |    val size: Int = 0
        |
        |    fun size(extra: Int): Int = size + extra
        |}
        |
        |class Other {
        |    fun size(): Int = 1
        |}
        |""".trimMargin()
    private val sizeProperty = symbol("size", SymbolKind.PROPERTY, range(1, 4, 1, 21), 1, 8, type = ": Int")
    private val sizeFunction = symbol("size", SymbolKind.FUNCTION, range(3, 4, 3, 44), 3, 8, parameterTypes = "Int", type = "(Int): Int")
    private val sizedClass = symbol("Sized", SymbolKind.CLASS, range(0, 0, 4, 1), 0, 6, children = listOf(sizeProperty, sizeFunction))
    private val otherSize = symbol("size", SymbolKind.FUNCTION, range(7, 4, 7, 23), 7, 8, parameterTypes = "", type = "(): Int")
    private val other = symbol("Other", SymbolKind.CLASS, range(6, 0, 8, 1), 6, 6, children = listOf(otherSize))

    private suspend fun readSized(symbol: String) = readSymbol(sized, sizedClass, other)("src/A.kt", symbol)

    @Test
    fun `a bare name prefers the property over a same-named function beside it, and parentheses select the function`() = runTest {
        assertEquals("// Sized/size in src/A.kt, L2-2", assertIs<ReadSymbolResult.Found>(readSized("Sized/size")).text.lines().first())
        assertEquals("// size(Int) in src/A.kt, L4-4", assertIs<ReadSymbolResult.Found>(readSized("size(Int)")).text.lines().first())
        assertEquals("// size() in src/A.kt, L8-8", assertIs<ReadSymbolResult.Found>(readSized("size()")).text.lines().first())
        assertIs<LookupFailure.NotFound>(readSized("Sized/size()").lookupFailure())
    }

    @Test
    fun `the preference only settles a tie inside one declaration, never between unrelated ones`() = runTest {
        assertEquals(listOf("Sized/size", "size()"), assertIs<LookupFailure.Ambiguous>(readSized("size").lookupFailure()).candidates.map { it.path })
    }

    @Test
    fun `accessors and local declarations are not addressable, even if the server reports them`() = runTest {
        val source = """
            |class Hidden {
            |    var counter: Int = 0
            |        get() = field
            |        set(value) {
            |            field = value
            |        }
            |
            |    fun outer(): Int {
            |        fun local(): Int = 1
            |        return local()
            |    }
            |}
            |""".trimMargin()
        // kotlin-lsp reports accessors as children named get and set; it omits local declarations, but another
        // server might report them as children of the function.
        val getter = CodeSymbol("get", SymbolKind.FUNCTION, range(2, 8, 2, 21), range(2, 8, 2, 21))
        val setter = CodeSymbol("set", SymbolKind.FUNCTION, range(3, 8, 5, 9), range(3, 8, 5, 9))
        val counter = symbol("counter", SymbolKind.PROPERTY, range(1, 4, 5, 9), 1, 8, type = ": Int", children = listOf(getter, setter))
        val local = symbol("local", SymbolKind.FUNCTION, range(8, 8, 8, 28), 8, 12, parameterTypes = "", type = "(): Int")
        val outer = symbol("outer", SymbolKind.FUNCTION, range(7, 4, 10, 5), 7, 8, parameterTypes = "", type = "(): Int", children = listOf(local))
        val hidden = symbol("Hidden", SymbolKind.CLASS, range(0, 0, 11, 1), 0, 6, children = listOf(counter, outer))
        val read = readSymbol(source, hidden)

        listOf("counter/get", "get", "set", "Hidden/counter/set", "outer/local", "local").forEach { path ->
            assertEquals(emptyList(), assertIs<LookupFailure.NotFound>(read("src/A.kt", path).lookupFailure(), path).suggestions.map { it.path }, path)
        }
        assertEquals("// counter in src/A.kt, L2-6", assertIs<ReadSymbolResult.Found>(read("src/A.kt", "counter")).text.lines().first())
    }

    private val forms = """
        |class Box(
        |    val width: Int,
        |    height: Int,
        |) {
        |    constructor(side: Int) : this(side, side)
        |}
        |
        |class Widget {
        |    companion object {
        |        fun create(): Widget = Widget()
        |    }
        |}
        |
        |class Outer {
        |    companion object Factory {
        |        fun create(): Outer = Outer()
        |    }
        |}
        |
        |enum class Tint(val code: Int) {
        |    PALE(1),
        |    DEEP(2) {
        |        fun hex(): String = "deep"
        |    },
        |}
        |
        |typealias Names = List<String>
        |""".trimMargin()

    // kotlin-lsp reports a primary constructor's val parameter as a variable, and gives constructors and unnamed
    // companions no separate name range.
    private val width = symbol("width", SymbolKind.VARIABLE, range(1, 4, 1, 18), 1, 8, type = ": Int")
    private val primary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(0, 9, 3, 1), range(0, 9, 3, 1), parameterTypes = "Int, Int", type = "(Int, Int)")
    private val secondary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(4, 4, 4, 45), range(4, 4, 4, 45), parameterTypes = "Int", type = "(Int)")
    private val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 5, 1), 0, 6, children = listOf(width, primary, secondary))
    private val widgetCreate = symbol("create", SymbolKind.FUNCTION, range(9, 8, 9, 39), 9, 12, parameterTypes = "", type = "(): Widget")
    private val companion = CodeSymbol("Companion", SymbolKind.OBJECT, range(8, 4, 10, 5), range(8, 4, 10, 5), listOf(widgetCreate))
    private val widget = symbol("Widget", SymbolKind.CLASS, range(7, 0, 11, 1), 7, 6, children = listOf(companion))
    private val outerCreate = symbol("create", SymbolKind.FUNCTION, range(15, 8, 15, 37), 15, 12, parameterTypes = "", type = "(): Outer")
    private val factory = symbol("Factory", SymbolKind.OBJECT, range(14, 4, 16, 5), 14, 21, children = listOf(outerCreate))
    private val outer = symbol("Outer", SymbolKind.CLASS, range(13, 0, 17, 1), 13, 6, children = listOf(factory))
    private val code = symbol("code", SymbolKind.VARIABLE, range(19, 16, 19, 29), 19, 20, type = ": Int")
    private val tintConstructor = CodeSymbol("Tint", SymbolKind.CONSTRUCTOR, range(19, 15, 19, 30), range(19, 15, 19, 30), parameterTypes = "Int", type = "(Int)")
    private val pale = symbol("PALE", SymbolKind.ENUM_ENTRY, range(20, 4, 20, 12), 20, 4)
    private val hex = symbol("hex", SymbolKind.FUNCTION, range(22, 8, 22, 34), 22, 12, parameterTypes = "", type = "(): String")
    private val deep = symbol("DEEP", SymbolKind.ENUM_ENTRY, range(21, 4, 23, 6), 21, 4, children = listOf(hex))
    private val tint = symbol("Tint", SymbolKind.ENUM, range(19, 0, 24, 1), 19, 11, children = listOf(code, tintConstructor, pale, deep))
    private val names = symbol("Names", SymbolKind.CLASS, range(26, 0, 26, 30), 26, 10)

    private suspend fun readForm(symbol: String) =
        readSymbol(forms, box, widget, outer, tint, names)("src/A.kt", symbol)

    private suspend fun headerOf(symbol: String) = assertIs<ReadSymbolResult.Found>(readForm(symbol), symbol).text.lines().first()

    @Test
    fun `constructors are named constructor, and a primary constructor's val parameters are properties`() = runTest {
        assertEquals("// Box/constructor(Int) in src/A.kt, L5-5", headerOf("Box/constructor(Int)"))
        // The primary constructor starts on the class's line, so it can be named but not read on its own.
        assertIs<LookupFailure.SharedLine>(readForm("Box/constructor(Int, Int)").lookupFailure())
        assertEquals(listOf("constructor(Int, Int)", "Box/constructor(Int)", "Tint/constructor"), assertIs<LookupFailure.Ambiguous>(readForm("constructor").lookupFailure()).candidates.map { it.path })
        // Resolved as a property; its range stops before the comma, which its span takes in (#68).
        assertEquals("// width in src/A.kt, L2-2", headerOf("Box/width"))
        assertIs<LookupFailure.NotFound>(readForm("Box/height").lookupFailure())
        assertIs<LookupFailure.NotFound>(readForm("Box/Box").lookupFailure())
    }

    @Test
    fun `a companion is Companion, or its own name when it has one`() = runTest {
        assertEquals("// Companion/create in src/A.kt, L10-10", headerOf("Widget/Companion/create"))
        assertEquals("// Companion in src/A.kt, L9-11", headerOf("Widget/Companion"))
        assertEquals("// Factory/create in src/A.kt, L16-16", headerOf("Outer/Factory/create"))
        assertIs<LookupFailure.NotFound>(readForm("Outer/Companion/create").lookupFailure())
    }

    @Test
    fun `enum entries and the members of their bodies are addressable`() = runTest {
        assertEquals("// PALE in src/A.kt, L21-21", headerOf("Tint/PALE"))
        assertEquals("// DEEP in src/A.kt, L22-24", headerOf("Tint/DEEP"))
        assertEquals("// hex in src/A.kt, L23-23", headerOf("Tint/DEEP/hex()"))
    }

    @Test
    fun `a typealias is addressed by its name`() = runTest {
        assertEquals("// Names in src/A.kt, L27-27\ntypealias Names = List<String>", assertIs<ReadSymbolResult.Found>(readForm("Names")).text)
    }

    @Test
    fun `function-type and nullable receivers are written as in Kotlin, and their canonical paths read back`() = runTest {
        val source = "fun ((Int) -> Unit).twice(x: Int) = this(x)\n\nfun String?.orBlank(): String = this ?: \"\"\n"
        val twice = symbol("twice", SymbolKind.FUNCTION, range(0, 0, 0, 43), 0, 20, parameterTypes = "Int", type = "(Int): Unit", receiver = "((Int) -> Unit)")
        val orBlank = symbol("orBlank", SymbolKind.FUNCTION, range(2, 0, 2, 42), 2, 12, parameterTypes = "", type = "(): String", receiver = "String?")
        val read = readSymbol(source, twice, orBlank)

        assertEquals("// ((Int) -> Unit).twice in src/A.kt, L1-1", assertIs<ReadSymbolResult.Found>(read("src/A.kt", "((Int)->Unit).twice")).text.lines().first())
        assertIs<ReadSymbolResult.Found>(read("src/A.kt", "((Int) -> Unit).twice"))
        assertIs<ReadSymbolResult.Found>(read("src/A.kt", "((Int) -> Unit).twice(Int)"))
        assertEquals("// String?.orBlank in src/A.kt, L3-3", assertIs<ReadSymbolResult.Found>(read("src/A.kt", "String?.orBlank")).text.lines().first())
        assertIs<LookupFailure.InvalidPath>(read("src/A.kt", "(Int)x").lookupFailure())
    }
}
