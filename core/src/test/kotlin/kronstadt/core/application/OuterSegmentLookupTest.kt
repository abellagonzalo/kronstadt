package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.SymbolCandidate
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.range
import kronstadt.core.domain.symbol
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A nested Symbol Path without a file is searched by its outermost indexed name, then by its leaf when that search is
 * incomplete or its files hold no match (#215).
 */
class OuterSegmentLookupTest {
    private val root = Path.of("/project")

    private val sKt = "src/main/kotlin/com/example/s/Shape.kt"
    private val sSource = """
        |package com.example.s
        |
        |class Shape {
        |    fun area(): Int = 1
        |
        |    class Edge {
        |        fun length(): Int = 2
        |    }
        |}
        |""".trimMargin()
    private val sArea = symbol("area", SymbolKind.FUNCTION, range(3, 4, 3, 23), 3, 8, parameterTypes = "", type = "(): Int")
    private val length = symbol("length", SymbolKind.FUNCTION, range(6, 8, 6, 29), 6, 12, parameterTypes = "", type = "(): Int")
    private val edge = symbol("Edge", SymbolKind.CLASS, range(5, 4, 7, 5), 5, 10, children = listOf(length))
    private val sShape = symbol("Shape", SymbolKind.CLASS, range(2, 0, 8, 1), 2, 6, children = listOf(sArea, edge))

    private val tKt = "src/main/kotlin/com/example/t/Shape.kt"
    private val tSource = "package com.example.t\n\nclass Shape {\n    fun area(): Int = 2\n}\n"
    private val tArea = symbol("area", SymbolKind.FUNCTION, range(3, 4, 3, 23), 3, 8, parameterTypes = "", type = "(): Int")
    private val tShape = symbol("Shape", SymbolKind.CLASS, range(2, 0, 4, 1), 2, 6, children = listOf(tArea))

    private val uKt = "src/main/kotlin/com/example/u/Shape.kt"
    private val uSource = "package com.example.u\n\nclass Shape\n"
    private val uShape = symbol("Shape", SymbolKind.CLASS, range(2, 0, 2, 11), 2, 6)

    private val vKt = "src/main/kotlin/com/example/v/Shape.kt"
    private val vSource = "package com.example.v\n\nclass Shape {\n    fun perimeter(): Int = 4\n}\n"
    private val perimeter = symbol("perimeter", SymbolKind.FUNCTION, range(3, 4, 3, 28), 3, 8, parameterTypes = "", type = "(): Int")
    private val vShape = symbol("Shape", SymbolKind.CLASS, range(2, 0, 4, 1), 2, 6, children = listOf(perimeter))

    private fun file(path: String) = root.resolve(path)

    /** Four classes named `Shape`, one of them left out; the nested `Edge` is left out too: index gaps. */
    private val index = listOf(
        SymbolLocation("Shape", SymbolKind.CLASS, file(sKt), sShape.range),
        SymbolLocation("area", SymbolKind.FUNCTION, file(sKt), sArea.range, "Shape"),
        SymbolLocation("length", SymbolKind.FUNCTION, file(sKt), length.range, "Edge"),
        SymbolLocation("Shape", SymbolKind.CLASS, file(tKt), tShape.range),
        SymbolLocation("area", SymbolKind.FUNCTION, file(tKt), tArea.range, "Shape"),
        SymbolLocation("Shape", SymbolKind.CLASS, file(uKt), uShape.range),
        // The fourth `Shape` is only listed by its member.
        SymbolLocation("perimeter", SymbolKind.FUNCTION, file(vKt), perimeter.range, "Shape"),
    )

    private fun intelligence(searchCap: Int? = null, timedOutQueries: Set<String> = emptySet()) = FakeCodeIntelligence(
        index = index,
        searchCap = searchCap,
        timedOutQueries = timedOutQueries,
        symbolsByFile = mapOf(file(sKt) to listOf(sShape), file(tKt) to listOf(tShape), file(uKt) to listOf(uShape), file(vKt) to listOf(vShape)),
    )

    private val files = FakeFileStore(root, mapOf(sKt to sSource, tKt to tSource, uKt to uSource, vKt to vSource))

    private suspend fun read(symbol: String, intelligence: FakeCodeIntelligence) = ReadSymbol(files, intelligence)(null, symbol)

    @Test
    fun `a member nested two deep resolves from its outermost name alone`() = runTest {
        val intelligence = intelligence()
        assertEquals("length in $sKt, L7-7", assertIs<ReadSymbolResult.Found>(read("Shape/Edge/length", intelligence)).location)
        assertEquals(listOf("Shape"), intelligence.searched)
    }

    @Test
    fun `two files declaring the path are ambiguous from the outermost name alone`() = runTest {
        val intelligence = intelligence()
        val result = assertIs<LookupFailure.Ambiguous>(read("Shape/area", intelligence).lookupFailure())
        assertEquals(listOf(SymbolCandidate("area", sKt), SymbolCandidate("area", tKt)), result.candidates)
        assertEquals(listOf("Shape"), intelligence.searched)
    }

    @Test
    fun `an incomplete outer search falls back to the leaf, which resolves`() = runTest {
        // Capped at three, the page of three `Shape`s may have left one out; the one `length` is listed in full.
        val intelligence = intelligence(searchCap = 3)
        assertEquals("length in $sKt, L7-7", assertIs<ReadSymbolResult.Found>(read("Shape/Edge/length", intelligence)).location)
        assertEquals(listOf("Shape", "length"), intelligence.searched)
    }

    @Test
    fun `when the leaf search is incomplete too, it asks for a file`() = runTest {
        assertEquals(
            ReadSymbolResult.LookupFailed(LookupFailure.TooManyDeclarations("Shape/Edge/length", "length")),
            read("Shape/Edge/length", intelligence(searchCap = 1)),
        )
    }

    @Test
    fun `an outer name the index left out falls back to the leaf, which resolves`() = runTest {
        val intelligence = intelligence()
        assertEquals("length in $sKt, L7-7", assertIs<ReadSymbolResult.Found>(read("Edge/length", intelligence)).location)
        assertEquals(listOf("Edge", "length"), intelligence.searched)
    }

    @Test
    fun `outer files that don't hold the path fall back to the leaf, which resolves`() = runTest {
        val intelligence = intelligence()
        assertEquals("perimeter in $vKt, L4-4", assertIs<ReadSymbolResult.Found>(read("Shape/perimeter", intelligence)).location)
        assertEquals(listOf("Shape", "perimeter"), intelligence.searched)
    }

    @Test
    fun `an outer search that times out fails fast, without searching the leaf`() = runTest {
        val intelligence = intelligence(timedOutQueries = setOf("Shape"))
        assertEquals(
            ReadSymbolResult.LookupFailed(LookupFailure.SearchTimedOut("Shape/area", "Shape")),
            read("Shape/area", intelligence),
        )
        assertEquals(listOf("Shape"), intelligence.searched)
    }

    @Test
    fun `a leaf search that times out after the fallback fails fast too`() = runTest {
        assertEquals(
            ReadSymbolResult.LookupFailed(LookupFailure.SearchTimedOut("Edge/length", "length")),
            read("Edge/length", intelligence(timedOutQueries = setOf("length"))),
        )
    }
}
