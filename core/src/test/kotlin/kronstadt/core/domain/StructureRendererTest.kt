package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class StructureRendererTest {
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

    private val local = symbol("cached", SymbolKind.VARIABLE, range(6, 8, 6, 30), 6, 12, type = ": User?")
    private val find = symbol("find", SymbolKind.FUNCTION, range(5, 4, 8, 5), 5, 8, parameterTypes = "UserId", type = "(UserId): User?", children = listOf(local))
    private val count = symbol("count", SymbolKind.FUNCTION, range(10, 4, 10, 35), 10, 8, parameterTypes = "", type = "(): Int")
    private val clazz = symbol("UserService", SymbolKind.CLASS, range(2, 0, 11, 1), 4, 6, children = listOf(count, find))

    @Test
    fun `prints canonical paths and signatures without bodies, nested and sorted by position`() {
        val expected = """
        // src/UserService.kt
        class UserService  // L3-12
          fun find  (UserId): User?  // L6-9
          fun count  (): Int  // L11-11
    """.trimIndent()
        assertEquals(expected, StructureRenderer.render(analysedFile(doc, listOf(clazz), "src/UserService.kt")))
    }

    @Test
    fun `the skeleton starts with a header line naming the file, as shown, and nothing wraps it`() {
        assertEquals("// src/x.kt\nfun count  (): Int  // L11-11", StructureRenderer.render(analysedFile(doc, listOf(count), "src/x.kt")))
    }

    @Test
    fun `overloads look different and their paths carry the signature`() {
        val doc = TextDocument("fun parse(s: String): Int = 1\nfun parse(s: String, radix: Int): Int = 2\n")
        val one = symbol("parse", SymbolKind.FUNCTION, range(0, 0, 0, 29), 0, 4, parameterTypes = "String", type = "(String): Int")
        val two = symbol("parse", SymbolKind.FUNCTION, range(1, 0, 1, 41), 1, 4, parameterTypes = "String, Int", type = "(String, Int): Int")

        val expected = """
            // P.kt
            fun parse(String)  (String): Int  // L1-1
            fun parse(String, Int)  (String, Int): Int  // L2-2
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render(analysedFile(doc, listOf(one, two), "P.kt")))
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
        val primary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(0, 9, 0, 25), range(0, 9, 0, 25), parameterTypes = "Int", type = "(Int)")
        val width = symbol("width", SymbolKind.PROPERTY, range(0, 10, 0, 24), 0, 14, type = ": Int")
        val secondary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(1, 4, 1, 27), range(1, 4, 1, 27), parameterTypes = "", type = "()")
        val first = symbol("first", SymbolKind.PROPERTY, range(3, 4, 3, 42), 3, 15, type = ": Char", receiver = "String")
        val shout = symbol("shout", SymbolKind.FUNCTION, range(4, 4, 4, 44), 4, 15, parameterTypes = "", type = "(): String", receiver = "String")
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 5, 1), 0, 6, children = listOf(primary, width, secondary, first, shout))

        // The primary constructor and its property share the class's line, so they report their declaration's lines.
        val expected = """
            // Box.kt
            class Box  // L1-6
              constructor(Int)  // L1-1
              val width  : Int  // L1-1
              constructor()  // L2-2
              val String.first  : Char  // L4-4
              fun String.shout  (): String  // L5-5
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render(analysedFile(doc, listOf(box), "Box.kt")))
    }

    @Test
    fun `a val parameter of a trailing-comma constructor lists its Symbol Span's lines, KDoc to comma`() {
        val doc = TextDocument("class Box(\n    /** Across. */\n    val width: Int,\n    val height: Int, // tall\n)\n")
        val width = symbol("width", SymbolKind.PROPERTY, range(1, 4, 2, 18), 2, 8, type = ": Int")
        val height = symbol("height", SymbolKind.PROPERTY, range(3, 4, 3, 19), 3, 8, type = ": Int")
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 4, 1), 0, 6, children = listOf(width, height))

        // The lines come from a whole-line span that takes in the comma, not from the fallback to the declaration's.
        assertEquals(
            listOf("    /** Across. */\n    val width: Int,", "    val height: Int, // tall"),
            listOf(width, height).map { assertIs<SymbolSpan.Computation.Computed>(SymbolSpan.of(doc, it)).span.text },
        )
        val expected = """
            // Box.kt
            class Box  // L1-5
              val width  : Int  // L2-3
              val height  : Int  // L4-4
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render(analysedFile(doc, listOf(box), "Box.kt")))
    }

    @Test
    fun `members of enum entry bodies are listed under their entry`() {
        val doc = TextDocument("enum class Color {\n    RED,\n    BLUE {\n        fun hex() = 1\n    };\n}\n")
        val red = symbol("RED", SymbolKind.ENUM_ENTRY, range(1, 4, 1, 7), 1, 4)
        val hex = symbol("hex", SymbolKind.FUNCTION, range(3, 8, 3, 21), 3, 12, parameterTypes = "", type = "(): Int")
        val blue = symbol("BLUE", SymbolKind.ENUM_ENTRY, range(2, 4, 4, 5), 2, 4, children = listOf(hex))
        val color = symbol("Color", SymbolKind.ENUM, range(0, 0, 5, 1), 0, 11, children = listOf(red, blue))

        val expected = """
            // Color.kt
            enum class Color  // L1-6
              entry RED  // L2-2
              entry BLUE  // L3-5
                fun hex  (): Int  // L4-4
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render(analysedFile(doc, listOf(color), "Color.kt")))
    }

    @Test
    fun `a file without declarations renders nothing`() {
        assertEquals("", StructureRenderer.render(analysedFile(TextDocument("plugins {}\n"), emptyList(), "build.gradle.kts")))
    }
}
