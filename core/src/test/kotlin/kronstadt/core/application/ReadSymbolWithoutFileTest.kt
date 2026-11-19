package kronstadt.core.application

import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** Symbol Paths without a file (ADR 0002): `workspace/symbol` supplies candidate files, `documentSymbol` resolves. */
class ReadSymbolWithoutFileTest {
    private val root = Path.of("/project")

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    private fun symbol(name: String, kind: SymbolKind, range: TextRange, nameLine: Int, nameChar: Int, detail: String? = null, children: List<CodeSymbol> = emptyList()) =
        CodeSymbol(name, kind, range, range(nameLine, nameChar, nameLine, nameChar + name.length), children, detail)

    private val controllerKt = "src/main/kotlin/com/example/web/OrderController.kt"
    private val controllerSource = """
        |package com.example.web
        |
        |class OrderController {
        |    fun create(): Int = 1
        |}
        |""".trimMargin()
    private val create = symbol("create", SymbolKind.FUNCTION, range(3, 4, 3, 25), 3, 8, "(): Int")
    private val controller = symbol("OrderController", SymbolKind.CLASS, range(2, 0, 4, 1), 2, 6, children = listOf(create))

    private val alphaKt = "src/main/kotlin/com/example/a/Config.kt"
    private val alphaSource = "package com.example.a\n\nclass Config(val port: Int)\n"
    private val alphaConfig = symbol("Config", SymbolKind.CLASS, range(2, 0, 2, 27), 2, 6)

    private val betaKt = "src/main/kotlin/com/example/b/Config.kt"
    private val betaSource = "/* header */\npackage com.example.b\n\nclass Config(val host: String)\n"
    private val betaConfig = symbol("Config", SymbolKind.CLASS, range(3, 0, 3, 30), 3, 6)

    private val boxKt = "src/main/kotlin/com/example/Box.kt"
    private val boxSource = """
        |package com.example
        |
        |class Box(val width: Int) {
        |    constructor() : this(0)
        |
        |    fun pack(): Int {
        |        fun local(): Int = width
        |        return local()
        |    }
        |}
        |
        |fun String.shout(): String = uppercase()
        |""".trimMargin()
    // kotlin-lsp names constructors after their class and omits local functions from documentSymbol.
    private val boxPrimary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(2, 9, 2, 25), range(2, 9, 2, 25), detail = "(Int)")
    private val boxSecondary = CodeSymbol("Box", SymbolKind.CONSTRUCTOR, range(3, 4, 3, 27), range(3, 4, 3, 27), detail = "()")
    private val pack = symbol("pack", SymbolKind.FUNCTION, range(5, 4, 8, 5), 5, 8, "(): Int")
    private val box = symbol("Box", SymbolKind.CLASS, range(2, 0, 9, 1), 2, 6, children = listOf(boxPrimary, boxSecondary, pack))
    private val shout = symbol("shout", SymbolKind.FUNCTION, range(11, 0, 11, 41), 11, 11, "String.(): String")

    private val registryKt = "src/main/kotlin/com/example/Registry.kt"
    private val registrySource = """
        |package com.example
        |
        |class Registry {
        |    companion object {
        |        fun empty(): Registry = Registry()
        |    }
        |}
        |""".trimMargin()
    private val empty = symbol("empty", SymbolKind.FUNCTION, range(4, 8, 4, 42), 4, 12, "(): Registry")
    private val registryCompanion = CodeSymbol("Companion", SymbolKind.OBJECT, range(3, 4, 5, 5), range(3, 4, 5, 5), listOf(empty))
    private val registry = symbol("Registry", SymbolKind.CLASS, range(2, 0, 6, 1), 2, 6, children = listOf(registryCompanion))

    // A block comment above the real header holds a line that starts with `package`.
    private val widgetKt = "src/main/kotlin/com/example/g/Widget.kt"
    private val widgetSource = "/*\npackage x.y\n*/\npackage com.example.g\n\nclass Widget\n"
    private val widget = symbol("Widget", SymbolKind.CLASS, range(5, 0, 5, 12), 5, 6)

    // A default-package file whose raw string has a line that starts with `package`.
    private val templateKt = "src/main/kotlin/Template.kt"
    private val templateSource = "class Template {\n    val text = \"\"\"\npackage other.pkg\n\"\"\"\n}\n"
    private val template = symbol("Template", SymbolKind.CLASS, range(0, 0, 4, 1), 0, 6)

    private fun file(path: String) = root.resolve(path)

    /** kotlin-lsp's index: one location per declaration, searched by substring. */
    private val index = listOf(
        SymbolLocation("create", SymbolKind.FUNCTION, file(controllerKt), create.range, "OrderController"),
        SymbolLocation("OrderController", SymbolKind.CLASS, file(controllerKt), controller.range),
        SymbolLocation("Config", SymbolKind.CLASS, file(alphaKt), alphaConfig.range),
        SymbolLocation("Config", SymbolKind.CLASS, file(betaKt), betaConfig.range),
        // Top-level symbols come twice, local functions are listed, and extension receivers are missing.
        SymbolLocation("Box", SymbolKind.CLASS, file(boxKt), box.range),
        SymbolLocation("Box", SymbolKind.CLASS, file(boxKt), box.range),
        SymbolLocation("Box", SymbolKind.CONSTRUCTOR, file(boxKt), boxSecondary.range, "Box"),
        SymbolLocation("pack", SymbolKind.FUNCTION, file(boxKt), pack.range, "Box"),
        SymbolLocation("local", SymbolKind.FUNCTION, file(boxKt), range(6, 8, 6, 32), "pack"),
        SymbolLocation("shout", SymbolKind.FUNCTION, file(boxKt), shout.range),
        // An unnamed companion isn't indexed under its name.
        SymbolLocation("Registry", SymbolKind.CLASS, file(registryKt), registry.range),
        SymbolLocation("empty", SymbolKind.FUNCTION, file(registryKt), empty.range, "Companion"),
        SymbolLocation("Widget", SymbolKind.CLASS, file(widgetKt), widget.range),
        SymbolLocation("Template", SymbolKind.CLASS, file(templateKt), template.range),
    )

    private fun intelligence(notReadyPhase: String? = null, searchCap: Int? = null) = FakeCodeIntelligence(
        index = index,
        notReadyPhase = notReadyPhase,
        searchCap = searchCap,
        symbolsByFile = mapOf(
            file(controllerKt) to listOf(controller),
            file(alphaKt) to listOf(alphaConfig),
            file(betaKt) to listOf(betaConfig),
            file(boxKt) to listOf(box, shout),
            file(registryKt) to listOf(registry),
            file(widgetKt) to listOf(widget),
            file(templateKt) to listOf(template),
        ),
    )

    private val files = FakeFileStore(
        root,
        mapOf(
            controllerKt to controllerSource, alphaKt to alphaSource, betaKt to betaSource, boxKt to boxSource,
            registryKt to registrySource, widgetKt to widgetSource, templateKt to templateSource,
        ),
    )

    private suspend fun read(symbol: String, intelligence: FakeCodeIntelligence = intelligence()) =
        ReadSymbol(files, intelligence)(null, symbol)

    @Test
    fun `a Symbol Path without a file resolves project-wide`() = runTest {
        val result = assertIs<ReadSymbolResult.Found>(read("OrderController/create"))
        assertEquals("// create in $controllerKt, L4-4\n    fun create(): Int = 1", result.text)
    }

    @Test
    fun `same-named classes in two packages are ambiguous, and each candidate names its file`() = runTest {
        val result = assertIs<ReadSymbolResult.Ambiguous>(read("Config"))
        assertEquals(null, result.file)
        assertEquals(listOf(SymbolCandidate("Config", alphaKt), SymbolCandidate("Config", betaKt)), result.candidates)
    }

    @Test
    fun `a dotted package prefix narrows the candidates, even below a file comment`() = runTest {
        assertEquals("// Config in $alphaKt, L3-3", assertIs<ReadSymbolResult.Found>(read("com.example.a/Config")).text.lines().first())
        assertEquals("// Config in $betaKt, L4-4", assertIs<ReadSymbolResult.Found>(read("com.example.b/Config")).text.lines().first())
        assertIs<ReadSymbolResult.Found>(read("com.example.web/OrderController/create"))
    }

    @Test
    fun `a package prefix anchors at the top level and matches the whole package exactly`() = runTest {
        assertIs<ReadSymbolResult.NotFound>(read("com.example.web/create"))
        assertIs<ReadSymbolResult.NotFound>(read("com.example/Config"))
        assertIs<ReadSymbolResult.NotFound>(read("example.a/Config"))
    }

    @Test
    fun `a wrong package suggests the Symbol where it really is`() = runTest {
        val result = assertIs<ReadSymbolResult.NotFound>(read("com.example.c/Config"))
        assertEquals(listOf(SymbolCandidate("Config", alphaKt), SymbolCandidate("Config", betaKt)), result.suggestions)
    }

    @Test
    fun `the package comes from the header, not from a comment above it`() = runTest {
        assertEquals("// Widget in $widgetKt, L6-6", assertIs<ReadSymbolResult.Found>(read("com.example.g/Widget")).text.lines().first())
        assertIs<ReadSymbolResult.NotFound>(read("x.y/Widget"))
    }

    @Test
    fun `a default-package file isn't matched by a package line in its body`() = runTest {
        assertIs<ReadSymbolResult.NotFound>(read("other.pkg/Template"))
    }

    @Test
    fun `waits for the index, then fails with not ready and searches nothing`() = runTest {
        val intelligence = intelligence(notReadyPhase = "Indexing")
        assertEquals(ReadSymbolResult.NotReady("Indexing", 5.seconds), ReadSymbol(files, intelligence, 5.seconds)(null, "Config"))
        assertEquals(emptyList(), intelligence.searched)
    }

    @Test
    fun `answers once the index becomes ready while waiting`() = runTest {
        val intelligence = intelligence(notReadyPhase = "Indexing")
        val reading = async { read("OrderController", intelligence) }
        intelligence.becomeReady()
        assertIs<ReadSymbolResult.Found>(reading.await())
    }

    @Test
    fun `with a file it still answers without waiting for the index`() = runTest {
        val read = ReadSymbol(files, intelligence(notReadyPhase = "Indexing"), 5.seconds)
        assertIs<ReadSymbolResult.Found>(read(alphaKt, "Config"))
    }

    @Test
    fun `a miss suggests close names, with their files`() = runTest {
        // The index matches substrings only, so a typo is looked up by a prefix of the name.
        val result = assertIs<ReadSymbolResult.NotFound>(read("Confgi"))
        assertEquals(null, result.file)
        assertEquals(listOf(SymbolCandidate("Config", alphaKt), SymbolCandidate("Config", betaKt)), result.suggestions)
        assertEquals(emptyList(), assertIs<ReadSymbolResult.NotFound>(read("Unheard")).suggestions)
    }

    @Test
    fun `the index only names candidate files, whose own trees decide`() = runTest {
        // Listed twice by the index, yet one Symbol.
        assertEquals("// Box in $boxKt, L3-10", assertIs<ReadSymbolResult.Found>(read("Box")).text.lines().first())
        // Listed by the index, but local declarations aren't addressable.
        assertIs<ReadSymbolResult.NotFound>(read("pack/local"))
        assertIs<ReadSymbolResult.NotFound>(read("local"))
        // Indexed without its receiver, which the tree has.
        assertEquals("// String.shout in $boxKt, L12-12", assertIs<ReadSymbolResult.Found>(read("String.shout")).text.lines().first())
        assertEquals(listOf(SymbolCandidate("String.shout", boxKt)), assertIs<ReadSymbolResult.NotFound>(read("shout")).suggestions)
    }

    @Test
    fun `a constructor is found through its class's name`() = runTest {
        assertEquals("// constructor() in $boxKt, L4-4", assertIs<ReadSymbolResult.Found>(read("Box/constructor()")).text.lines().first())
        assertEquals(
            listOf(SymbolCandidate("constructor(Int)", boxKt), SymbolCandidate("constructor()", boxKt)),
            assertIs<ReadSymbolResult.Ambiguous>(read("com.example/Box/constructor")).candidates,
        )
    }

    @Test
    fun `closes every document it analysed, whatever the outcome`() = runTest {
        listOf("Config", "com.example.a/Config", "Box", "Confgi", "local").forEach { path ->
            val intelligence = intelligence()
            read(path, intelligence)
            intelligence.assertOneSessionClosedEverything(path)
        }
    }

    @Test
    fun `an unnamed companion is found through its class's name`() = runTest {
        assertEquals("// Companion in $registryKt, L4-6", assertIs<ReadSymbolResult.Found>(read("Registry/Companion")).text.lines().first())
        assertIs<ReadSymbolResult.Found>(read("Registry/Companion/empty"))
    }

    @Test
    fun `a package prefix is only for paths without a file`() = runTest {
        val intelligence = intelligence()
        assertIs<ReadSymbolResult.InvalidPath>(ReadSymbol(files, intelligence)(alphaKt, "com.example.a/Config"))
        assertEquals(emptyList(), intelligence.analysed)
    }

    // ADR 0002: the index stops at a maximum number of results, and a full page may have left a declaring file out.
    @Test
    fun `a name the index may not have listed in full is never resolved without a file, not even to one match`() = runTest {
        // Capped at one result, the index returns one of the two Configs: resolving it would silently pick that one.
        val capped = intelligence(searchCap = 1)

        assertEquals(ReadSymbolResult.TooManyDeclarations("Config", "Config"), read("Config", capped))
        // The index is searched by the name alone, so a package prefix narrows nothing.
        assertEquals(
            ReadSymbolResult.TooManyDeclarations("com.example.a/Config", "Config"),
            read("com.example.a/Config", capped),
        )
        // A constructor is looked up through its class's name.
        assertEquals(ReadSymbolResult.TooManyDeclarations("Box/constructor()", "Box"), read("Box/constructor()", capped))
        // With a file, the index isn't asked.
        assertIs<ReadSymbolResult.Found>(ReadSymbol(files, capped)(alphaKt, "Config"))
        // A search that stops short of the cap is complete, so it resolves as before.
        assertIs<ReadSymbolResult.Found>(read("OrderController/create", intelligence(searchCap = 2)))
    }

    @Test
    fun `a full page that ends in other names listed every exact match, so the name still resolves`() = runTest {
        // `createAll` merely contains the name; capped at two, the page ends in it, after the one exact `create`.
        val createAll = SymbolLocation("createAll", SymbolKind.FUNCTION, file(boxKt), pack.range, "Box")
        val capped = FakeCodeIntelligence(
            index = index + createAll,
            symbolsByFile = mapOf(file(controllerKt) to listOf(controller), file(boxKt) to listOf(box, shout)),
            searchCap = 2,
        )

        val result = assertIs<ReadSymbolResult.Found>(read("create", capped))
        assertEquals("// create in $controllerKt, L4-4", result.text.lines().first())
    }
}
