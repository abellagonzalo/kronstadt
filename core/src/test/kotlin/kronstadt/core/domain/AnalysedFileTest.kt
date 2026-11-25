package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A file's Symbols: canonical paths, listings, in-file resolution and Symbol Spans. */
class AnalysedFileTest {
    private val document = TextDocument(
        """
        |package a
        |
        |/** Greets. */
        |class Greeter {
        |    fun greet() = 1
        |    fun greet(n: Int) = n
        |}
        |""".trimMargin(),
    )
    private val greet = symbol("greet", SymbolKind.FUNCTION, range(4, 4, 4, 19), 4, 8, parameterTypes = "", type = "(): Int")
    private val greetInt = symbol("greet", SymbolKind.FUNCTION, range(5, 4, 5, 25), 5, 8, parameterTypes = "Int", type = "(Int): Int")
    private val greeter = symbol("Greeter", SymbolKind.CLASS, range(2, 0, 6, 1), 3, 6, children = listOf(greet, greetInt))
    private val analysed = analysedFile(document, listOf(greeter))

    private fun path(text: String) = SymbolPath.parse(text).getOrThrow()

    @Test
    fun `listings show each Symbol's canonical path, kind, signature, depth and 1-based lines`() {
        assertEquals(
            listOf(
                SymbolListing(path("Greeter"), SymbolKind.CLASS, null, 0, 3, 7),
                SymbolListing(path("greet()"), SymbolKind.FUNCTION, "(): Int", 1, 5, 5),
                SymbolListing(path("greet(Int)"), SymbolKind.FUNCTION, "(Int): Int", 1, 6, 6),
            ),
            analysed.listings,
        )
    }

    @Test
    fun `a Symbol sharing a line with other code lists its declaration's lines`() {
        val document = TextDocument("class Box(val width: Int)\n")
        val width = symbol("width", SymbolKind.PROPERTY, range(0, 10, 0, 24), 0, 14, type = ": Int")
        val box = symbol("Box", SymbolKind.CLASS, range(0, 0, 0, 25), 0, 6, children = listOf(width))
        val analysed = analysedFile(document, listOf(box))
        val node = analysed.nodes.last()

        assertIs<SymbolSpan.Computation.SharedLine>(analysed.span(node))
        assertEquals(0..0, analysed.listedLines(node))
        assertEquals(1, analysed.listing(node).firstLine)
    }

    @Test
    fun `a path naming one Symbol resolves to it, with its canonical path`() {
        val found = assertIs<AnalysedFile.Resolution.Found>(analysed.resolve(path("Greeter/greet(Int)")))

        assertEquals(greetInt, found.node.symbol)
        assertEquals(path("greet(Int)"), found.path)
    }

    @Test
    fun `a path naming several Symbols is ambiguous, each shown as a candidate in the file`() {
        val ambiguous = assertIs<AnalysedFile.Resolution.Ambiguous>(analysed.resolve(path("greet")))

        assertEquals(listOf(greet, greetInt), ambiguous.matches.map { it.symbol })
        assertEquals(listOf(SymbolCandidate("greet()", "src/A.kt"), SymbolCandidate("greet(Int)", "src/A.kt")), ambiguous.candidates)
    }

    @Test
    fun `a path naming nothing suggests close names`() {
        val notFound = assertIs<AnalysedFile.Resolution.NotFound>(analysed.resolve(path("gret")))

        assertEquals(listOf(SymbolCandidate("greet()", "src/A.kt"), SymbolCandidate("greet(Int)", "src/A.kt")), notFound.suggestions)
    }

    /** `class FileLayout { companion object { fun of() = FileLayout() } }`. */
    private val fileLayout = run {
        val document = TextDocument(
            """
    |class FileLayout {
    |    companion object {
    |        fun of() = FileLayout()
    |    }
    |}
    |""".trimMargin(),
        )
        val of = symbol("of", SymbolKind.FUNCTION, range(2, 8, 2, 31), 2, 12, parameterTypes = "", type = "(): FileLayout")
        val companion = CodeSymbol("Companion", SymbolKind.OBJECT, range(1, 4, 3, 5), range(1, 4, 3, 5), listOf(of))
        analysedFile(document, listOf(symbol("FileLayout", SymbolKind.CLASS, range(0, 0, 4, 1), 0, 6, children = listOf(companion))))
    }

    @Test
    fun `a path missing a nested Symbol suggests it with the enclosing chain the path named`() {
        val notFound = assertIs<AnalysedFile.Resolution.NotFound>(fileLayout.resolve(path("FileLayout/of")))

        assertEquals(listOf(SymbolCandidate("FileLayout/Companion/of", "src/A.kt")), notFound.suggestions)
        assertEquals(fileLayout.path(notFound.close.single()), path("of"))
    }

    @Test
    fun `a suggestion longer than its canonical path keeps the signatures that tell overloads apart`() {
        val notFound = assertIs<AnalysedFile.Resolution.NotFound>(analysed.resolve(path("Greeter/gret")))

        assertEquals(
            listOf(SymbolCandidate("Greeter/greet()", "src/A.kt"), SymbolCandidate("Greeter/greet(Int)", "src/A.kt")),
            notFound.suggestions,
        )
    }

    @Test
    fun `each suggested path resolves to the suggested Symbol`() {
        listOf(fileLayout to "FileLayout/of", analysed to "Greeter/gret", analysed to "gret").forEach { (file, missed) ->
            val notFound = assertIs<AnalysedFile.Resolution.NotFound>(file.resolve(path(missed)))
            notFound.close.zip(notFound.suggestions).forEach { (node, suggestion) ->
                assertEquals(node, assertIs<AnalysedFile.Resolution.Found>(file.resolve(path(suggestion.path))).node)
            }
        }
    }

    /** `class Tools { val edit = 0; fun edit(n: Int) = n; fun edit(s: String) = s }`, one declaration per line. */
    private val tools = run {
        val document = TextDocument(
            """
        |class Tools {
        |    val edit = 0
        |    fun edit(n: Int) = n
        |    fun edit(s: String) = s
        |}
        |""".trimMargin(),
        )
        val property = symbol("edit", SymbolKind.PROPERTY, range(1, 4, 1, 16), 1, 8, type = ": Int")
        val editInt = symbol("edit", SymbolKind.FUNCTION, range(2, 4, 2, 24), 2, 8, parameterTypes = "Int", type = "(Int): Int")
        val editString = symbol("edit", SymbolKind.FUNCTION, range(3, 4, 3, 27), 3, 8, parameterTypes = "String", type = "(String): String")
        analysedFile(document, listOf(symbol("Tools", SymbolKind.CLASS, range(0, 0, 4, 1), 0, 6, children = listOf(property, editInt, editString))))
    }

    @Test
    fun `a bare name preferring a property to same-named functions resolves to the property and lists the functions`() {
        val found = assertIs<AnalysedFile.Resolution.Found>(tools.resolve(path("edit")))

        assertEquals(SymbolKind.PROPERTY, found.node.symbol.kind)
        assertEquals(listOf(path("edit(Int)"), path("edit(String)")), found.passedOver)
        found.passedOver.forEach { function ->
            val resolved = assertIs<AnalysedFile.Resolution.Found>(tools.resolve(function))
            assertEquals(SymbolKind.FUNCTION, resolved.node.symbol.kind)
            assertEquals(function, resolved.path)
        }
    }

    @Test
    fun `a path with a signature, or a name no function shares, passes nothing over`() {
        assertEquals(emptyList(), assertIs<AnalysedFile.Resolution.Found>(tools.resolve(path("edit(Int)"))).passedOver)
        assertEquals(emptyList(), assertIs<AnalysedFile.Resolution.Found>(tools.resolve(path("Tools"))).passedOver)
        assertEquals(emptyList(), assertIs<AnalysedFile.Resolution.Found>(analysed.resolve(path("Greeter/greet(Int)"))).passedOver)
    }

    @Test
    fun `the innermost Symbol Span holding some lines is the deepest Symbol's`() {
        assertEquals(4..4, analysed.innermostSpanHolding(4..4)?.let { it.firstLine..it.lastLine })
        assertEquals(2..6, analysed.innermostSpanHolding(4..5)?.let { it.firstLine..it.lastLine })
        assertNull(analysed.innermostSpanHolding(0..0))
    }
}
