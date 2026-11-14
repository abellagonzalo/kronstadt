package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class StructureRendererTest {
    private val source = """
        package demo

        @Service
        class UserService(private val repo: UserRepository) {
            fun find(id: UserId): User? {
                val cached = cache[id]
                return cached ?: repo.find(id)
            }

            fun count(): Int = repo.count()
        }
    """.trimIndent()
    private val doc = TextDocument(source)

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    private val local = CodeSymbol("cached", SymbolKind.VARIABLE, range(5, 8, 5, 30), range(5, 12, 5, 18))
    private val find = CodeSymbol("find", SymbolKind.FUNCTION, range(4, 4, 7, 5), range(4, 8, 4, 12), listOf(local))
    private val count = CodeSymbol("count", SymbolKind.FUNCTION, range(9, 4, 9, 35), range(9, 8, 9, 13))
    private val clazz = CodeSymbol("UserService", SymbolKind.CLASS, range(2, 0, 10, 1), range(3, 6, 3, 17), listOf(count, find))

    @Test
    fun `renders headers from source, nests members, skips function locals, sorts by position`() {
        val expected = """
            <file_structure path="src/UserService.kt">
            class UserService(private val repo: UserRepository)  // L3-11
              fun find(id: UserId): User?  // L5-8
              fun count(): Int = repo.count()  // L10-10
            </file_structure>
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render("src/UserService.kt", doc, listOf(clazz), xml = true))
    }

    @Test
    fun `does not repeat the header for primary constructors and same-line properties`() {
        // Shape reported by kotlin-lsp: the primary constructor and its properties are children of the class.
        val doc = TextDocument("class Repo(\n    val db: Db,\n) {\n    fun all() = db.all()\n}")
        val header = range(0, 6, 0, 10)
        val constructor = CodeSymbol("Repo", SymbolKind.CONSTRUCTOR, range(0, 10, 2, 1), header)
        val ownLine = CodeSymbol("db", SymbolKind.PROPERTY, range(1, 4, 1, 14), range(1, 8, 1, 10))
        val all = CodeSymbol("all", SymbolKind.FUNCTION, range(3, 4, 3, 24), range(3, 8, 3, 11))
        val clazz = CodeSymbol("Repo", SymbolKind.CLASS, range(0, 0, 4, 1), header, listOf(constructor, ownLine, all))

        val expected = """
            class Repo(  // L1-5
              val db: Db,  // L2-2
              fun all() = db.all()  // L4-4
        """.trimIndent()
        assertEquals(expected, StructureRenderer.render("Repo.kt", doc, listOf(clazz), xml = false))
    }

    @Test
    fun `plain output omits the wrapper`() {
        val out = StructureRenderer.render("x.kt", doc, listOf(count), xml = false)
        assertEquals("fun count(): Int = repo.count()  // L10-10", out)
    }
}
