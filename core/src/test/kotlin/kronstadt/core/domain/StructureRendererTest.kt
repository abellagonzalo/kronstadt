package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class StructureRendererTest {
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

    private val source = """
        |package demo
        |
        |/** Finds users. */
        |@Service
        |class UserService {
        |    fun find(id: UserId): User? {
        |        val cached = cache[id]
        |        return cached ?: repo.find(id)
        |    }
        |
        |    fun count(): Int = repo.count()
        |}
        |""".trimMargin()
    private val doc = TextDocument(source)

    private val local = symbol("cached", SymbolKind.VARIABLE, range(6, 8, 6, 30), 6, 12, ": User?")
    private val find = symbol("find", SymbolKind.FUNCTION, range(5, 4, 8, 5), 5, 8, "(UserId): User?", listOf(local))
    private val count = symbol("count", SymbolKind.FUNCTION, range(10, 4, 10, 35), 10, 8, "(): Int")
    private val clazz = symbol("UserService", SymbolKind.CLASS, range(2, 0, 11, 1), 4, 6, children = listOf(count, find))

    @Test
    fun `prints canonical paths and signatures without bodies, nested and sorted by position`() {
        val expected = """
            <file_structure path="src/UserService.kt">
            class UserService  // L3-12
              fun find  (UserId): User?  // L6-9
              fun count  (): Int  // L11-11
            </file_structure>
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render("src/UserService.kt", doc, listOf(clazz), xml = true))
    }

    @Test
    fun `plain output omits the wrapper`() {
        assertEquals("fun count  (): Int  // L11-11", StructureRenderer.render("x.kt", doc, listOf(count), xml = false))
    }

    @Test
    fun `overloads look different and their paths carry the signature`() {
        val doc = TextDocument("fun parse(s: String): Int = 1\nfun parse(s: String, radix: Int): Int = 2\n")
        val one = symbol("parse", SymbolKind.FUNCTION, range(0, 0, 0, 29), 0, 4, "(String): Int")
        val two = symbol("parse", SymbolKind.FUNCTION, range(1, 0, 1, 41), 1, 4, "(String, Int): Int")

        val expected = """
            fun parse(String)  (String): Int  // L1-1
            fun parse(String, Int)  (String, Int): Int  // L2-2
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render("P.kt", doc, listOf(one, two), xml = false))
    }

    @Test
    fun `shows constructors, primary-constructor properties and extensions in their path forms`() {
        val doc = TextDocument(
            """
            |class Box(val width: Int) {
            |    constructor() : this(0)
            |
            |    val String.first: Char get() = this[0]
            |    fun String.shout(): String = uppercase()
            |}
            |""".trimMargin(),
        )
        val primary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(0, 9, 0, 25), range(0, 9, 0, 25), detail = "(Int)")
        val width = symbol("width", SymbolKind.PROPERTY, range(0, 10, 0, 24), 0, 14, ": Int")
        val secondary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(1, 4, 1, 27), range(1, 4, 1, 27), detail = "()")
        val first = symbol("first", SymbolKind.PROPERTY, range(3, 4, 3, 42), 3, 15, "String.: Char")
        val shout = symbol("shout", SymbolKind.FUNCTION, range(4, 4, 4, 44), 4, 15, "String.(): String")
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 5, 1), 0, 6, children = listOf(primary, width, secondary, first, shout))

        // The primary constructor and its property share the class's line, so they report their declaration's lines.
        val expected = """
            class Box  // L1-6
              constructor(Int)  // L1-1
              val width  : Int  // L1-1
              constructor()  // L2-2
              val String.first  : Char  // L4-4
              fun String.shout  (): String  // L5-5
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render("Box.kt", doc, listOf(box), xml = false))
    }

    @Test
    fun `members of enum entry bodies are listed under their entry`() {
        val doc = TextDocument("enum class Color {\n    RED,\n    BLUE {\n        fun hex() = 1\n    };\n}\n")
        val red = symbol("RED", SymbolKind.ENUM_ENTRY, range(1, 4, 1, 7), 1, 4)
        val hex = symbol("hex", SymbolKind.FUNCTION, range(3, 8, 3, 21), 3, 12, "(): Int")
        val blue = symbol("BLUE", SymbolKind.ENUM_ENTRY, range(2, 4, 4, 5), 2, 4, children = listOf(hex))
        val color = symbol("Color", SymbolKind.ENUM, range(0, 0, 5, 1), 0, 11, children = listOf(red, blue))

        val expected = """
            enum class Color  // L1-6
              entry RED  // L2-2
              entry BLUE  // L3-5
                fun hex  (): Int  // L4-4
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render("Color.kt", doc, listOf(color), xml = false))
    }

    @Test
    fun `a file without declarations renders nothing`() {
        assertEquals("", StructureRenderer.render("build.gradle.kts", TextDocument("plugins {}\n"), emptyList(), xml = false))
    }
}
