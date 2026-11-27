package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.LibrarySource
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.range
import kronstadt.core.domain.symbol
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** `kt_get_definition`: where a name used inside a Symbol is declared, without the agent computing positions. */
class GetDefinitionTest {
    private val root = Path.of("/project")

    // The Greeter, documented, and a caller that uses it.

    private val greeterKt = "src/main/kotlin/com/example/Greeter.kt"
    private val greeterSource = """
        |package com.example
        |
        |/** Says hello. */
        |class Greeter(private val prefix: String) {
        |    /**
        |     * Greets [name].
        |     */
        |    @Deprecated("use greetAll")
        |    fun greet(name: String): String {
        |        return "${'$'}prefix, ${'$'}name"
        |    }
        |}
        |""".trimMargin()
    private val greet = symbol("greet", SymbolKind.FUNCTION, range(4, 4, 10, 5), 8, 8, parameterTypes = "String", type = "(String): String")
    private val greeter = symbol("Greeter", SymbolKind.CLASS, range(2, 0, 11, 1), 3, 6, children = listOf(greet))

    private val callerKt = "src/main/kotlin/com/example/Caller.kt"
    private val callerSource = """
        |package com.example
        |
        |fun welcome(guest: String): String {
        |    val greeter = Greeter("Hi")
        |    return greeter.greet(guest)
        |}
        |""".trimMargin()
    private val welcome = symbol("welcome", SymbolKind.FUNCTION, range(2, 0, 5, 1), 2, 4, parameterTypes = "String", type = "(String): String")

    private val listsKt = "src/main/kotlin/com/example/Lists.kt"
    private val listsSource = """
        |package com.example
        |
        |fun names(): Int = listOf("a").size + java.io.File("x").name.length
        |""".trimMargin()
    private val names = symbol("names", SymbolKind.FUNCTION, range(2, 0, 2, 67), 2, 4, parameterTypes = "", type = "(): Int")

    /** The name at [line]:[char] (zero-based) of the lists file. */
    private fun inLists(line: Int, char: Int) = root.resolve(listsKt) to Position(line, char)

    // A slice of the Kotlin standard library's sources, as kotlin-lsp finds them in its sources jar.

    private val stdlibSource = """
        |package kotlin.collections
        |
        |/**
        | * Returns a new read-only list of given elements.
        | */
        |public fun <T> listOf(vararg elements: T): List<T> =
        |    if (elements.size > 0) elements.asList() else emptyList()
        |
        |public interface List<out E> : Collection<E> {
        |    /** The number of elements. */
        |    override val size: Int
        |}
        |""".trimMargin()
    private val listOf = symbol("listOf", SymbolKind.FUNCTION, range(2, 0, 6, 61), 5, 15, parameterTypes = "vararg T", type = "(vararg T): List<T>")
    private val size = symbol("size", SymbolKind.PROPERTY, range(9, 4, 10, 26), 10, 17, type = ": Int")
    private val list = symbol("List", SymbolKind.INTERFACE, range(8, 0, 11, 1), 8, 17, children = listOf(size))
    private val stdlib = LibrarySource(TextDocument(stdlibSource), listOf(listOf, list))
    private val stdlibJar = "kotlin-stdlib-2.4.21-sources.jar"
    private val collectionsKt = "commonMain/kotlin/collections/Collections.kt"

    private fun inStdlib(symbol: CodeSymbol) =
        Definition.InLibrary(stdlibJar, collectionsKt, symbol.selectionRange, stdlib)

    private val legacyJava = "src/main/java/com/example/Legacy.java"
    private val legacySource = "package com.example;\n\npublic class Legacy {\n    public static String hello() { return \"hi\"; }\n}\n"

    private fun project(
        definitions: Map<Pair<Path, Position>, List<Definition>> = emptyMap(),
        notReadyPhase: String? = null,
        diagnosticsByText: Map<String, List<Diagnostic>> = emptyMap(),
        beforeDiagnostics: suspend () -> Unit = {},
        caller: String = callerSource,
    ): Pair<FakeFileStore, FakeCodeIntelligence> {
        val files = FakeFileStore(
            root,
            mapOf(greeterKt to greeterSource, callerKt to caller, listsKt to listsSource, legacyJava to legacySource),
        )
        val symbols = mapOf(greeterKt to listOf(greeter), callerKt to listOf(welcome), listsKt to listOf(names))
        val index = symbols.flatMap { (file, roots) ->
            fun all(s: CodeSymbol): List<CodeSymbol> = listOf(s) + s.children.flatMap(::all)
            roots.flatMap(::all).map { SymbolLocation(it.name, it.kind, root.resolve(file), it.selectionRange) }
        }
        val intelligence = FakeCodeIntelligence(
            notReadyPhase = notReadyPhase,
            symbolsByFile = symbols.mapKeys { (file, _) -> root.resolve(file) },
            index = index,
            definitions = definitions,
            diagnosticsByText = diagnosticsByText,
            beforeDiagnostics = beforeDiagnostics,
        )
        return files to intelligence
    }

    /** The name at [line]:[char] (zero-based) of the caller. */
    private fun inCaller(line: Int, char: Int) = root.resolve(callerKt) to Position(line, char)

    private fun inSource(file: String, symbol: CodeSymbol) = Definition.InSource(SourceLocation(root.resolve(file), symbol.selectionRange))

    @Test
    fun `a project target carries its listing, file, and KDoc and signature without the body`() = runTest {
        val (files, intelligence) = project(mapOf(inCaller(4, 19) to listOf(inSource(greeterKt, greet))))

        val result = GetDefinition(files, intelligence)("welcome", "greet")

        val found = assertIs<FoundDefinition.InSource>(assertIs<GetDefinitionResult.Found>(result).definitions.single())
        assertEquals(Triple("greet", 5, 11), Triple(found.listing.path.toString(), found.listing.firstLine, found.listing.lastLine))
        assertEquals(greeterKt, found.file)
        assertEquals(
            """
            |    /**
            |     * Greets [name].
            |     */
            |    @Deprecated("use greetAll")
            |    fun greet(name: String): String
            """.trimMargin(),
            found.head,
        )
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a name that occurs more than once in the Symbol Span fails and lists the lines where it occurs`() = runTest {
        val (files, intelligence) = project()

        assertEquals(
            GetDefinitionResult.NameNotExactlyOnce(
                name = "greeter",
                symbol = "welcome",
                file = callerKt,
                lines = listOf(SourceLine(4, "val greeter = Greeter(\"Hi\")"), SourceLine(5, "return greeter.greet(guest)")),
            ),
            GetDefinition(files, intelligence)("welcome", "greeter"),
        )
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a name that doesn't occur in the Symbol Span as a whole identifier fails with no lines`() = runTest {
        val (files, intelligence) = project()

        assertEquals(
            GetDefinitionResult.NameNotExactlyOnce("greete", "welcome", callerKt, emptyList()),
            GetDefinition(files, intelligence)("welcome", "greete"),
        )
    }

    /** A declaration in the caller, at [line]:[char] (zero-based), [length] long. */
    private fun inCallerAt(line: Int, char: Int, length: Int) =
        Definition.InSource(SourceLocation(root.resolve(callerKt), range(line, char, line, char + length)))

    @Test
    fun `with at, the name is looked up inside the snippet, and a local variable shows its enclosing Symbol Path and declaration line`() = runTest {
        val (files, intelligence) = project(mapOf(inCaller(4, 11) to listOf(inCallerAt(3, 8, 7))))

        val result = GetDefinition(files, intelligence)("welcome", "greeter", at = "greeter.greet(")

        assertEquals(
            GetDefinitionResult.Found(listOf(FoundDefinition.Local("greeter", "welcome", callerKt, 4, "val greeter = Greeter(\"Hi\")"))),
            result,
        )
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a parameter is local too`() = runTest {
        val (files, intelligence) = project(mapOf(inCaller(4, 25) to listOf(inCallerAt(2, 12, 5))))

        val result = GetDefinition(files, intelligence)("welcome", "guest", at = "greet(guest)")

        assertEquals(
            GetDefinitionResult.Found(listOf(FoundDefinition.Local("guest", "welcome", callerKt, 3, "fun welcome(guest: String): String {"))),
            result,
        )
    }

    @Test
    fun `an at snippet that doesn't occur exactly once fails with the Snippet's miss`() = runTest {
        val (files, intelligence) = project()
        val at = "  val greeter = Greeter(\"Hi\")\n  return greeter.greet(guest)"

        assertEquals(
            GetDefinitionResult.AtNotExactlyOnce(
                symbol = "welcome",
                file = callerKt,
                miss = Snippet.Miss(at, 0, emptyList(), IndentationHint(4, IndentationHint.Indents(line = 4, file = "    ", snippet = "  "))),
            ),
            GetDefinition(files, intelligence)("welcome", "greet", at = at),
        )
    }

    @Test
    fun `a name that isn't inside the at snippet fails`() = runTest {
        val (files, intelligence) = project()

        assertEquals(
            GetDefinitionResult.NameNotExactlyOnce("guest", "welcome", callerKt, emptyList()),
            GetDefinition(files, intelligence)("welcome", "guest", at = "greeter.greet"),
        )
    }

    @Test
    fun `a library target shows its fully qualified name, its jar, and its KDoc and signature, read-only`() = runTest {
        val (files, intelligence) = project(mapOf(inLists(2, 19) to listOf(inStdlib(listOf))))

        val result = GetDefinition(files, intelligence)("names", "listOf")

        val head = "/**\n * Returns a new read-only list of given elements.\n */\npublic fun <T> listOf(vararg elements: T): List<T>"
        assertEquals(
            GetDefinitionResult.Found(listOf(FoundDefinition.InLibrary("kotlin.collections.listOf", stdlibJar, collectionsKt, head))),
            result,
        )
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a library member's fully qualified name goes through its enclosing declarations`() = runTest {
        val (files, intelligence) = project(mapOf(inLists(2, 31) to listOf(inStdlib(size))))

        val result = GetDefinition(files, intelligence)("names", "size")

        val head = "    /** The number of elements. */\n    override val size: Int"
        assertEquals(
            GetDefinitionResult.Found(listOf(FoundDefinition.InLibrary("kotlin.collections.List.size", stdlibJar, collectionsKt, head))),
            result,
        )
    }

    @Test
    fun `a target without sources, such as a JDK class, shows the class it is in`() = runTest {
        val inFile = Definition.InLibrary("JDK java.base", "java/io/File.class", range(37, 63, 37, 70), source = null)
        val (files, intelligence) = project(mapOf(inLists(2, 56) to listOf(inFile)))

        val result = GetDefinition(files, intelligence)("names", "name")

        assertEquals(GetDefinitionResult.Found(listOf(FoundDefinition.LibraryWithoutSources("name", "java.io.File", "JDK java.base"))), result)
    }

    @Test
    fun `several definitions come in the order the code intelligence gives them`() = runTest {
        val (files, intelligence) = project(mapOf(inLists(2, 19) to listOf(inStdlib(listOf), inSource(greeterKt, greet))))

        val definitions = assertIs<GetDefinitionResult.Found>(GetDefinition(files, intelligence)("names", "listOf")).definitions

        assertEquals(2, definitions.size, definitions.toString())
        assertEquals("kotlin.collections.listOf", assertIs<FoundDefinition.InLibrary>(definitions[0]).qualifiedName)
        assertEquals("greet", assertIs<FoundDefinition.InSource>(definitions[1]).listing.path.toString())
    }

    @Test
    fun `an unresolved reference isn't an error, it says so with the compiler's diagnostic`() = runTest {
        val unresolved = Diagnostic(range(3, 18, 3, 25), Severity.ERROR, "Unresolved reference 'Greeter'.", "UNRESOLVED_REFERENCE")
        val (files, intelligence) = project(diagnosticsByText = mapOf(callerSource to listOf(unresolved)))

        assertEquals(
            GetDefinitionResult.Unresolved("Greeter", "welcome", 4, "UNRESOLVED_REFERENCE: Unresolved reference 'Greeter'."),
            GetDefinition(files, intelligence)("welcome", "Greeter"),
        )
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a lookup that fails with an exception still closes every document it opened`() = runTest {
        val (files, intelligence) = project(beforeDiagnostics = { throw IllegalStateException("language server died") })

        assertFailsWith<IllegalStateException> { GetDefinition(files, intelligence)("welcome", "Greeter") }

        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a keyword doesn't resolve either, and has no diagnostic`() = runTest {
        val (files, intelligence) = project()

        assertEquals(
            GetDefinitionResult.Unresolved("return", "welcome", 5, diagnostic = null),
            GetDefinition(files, intelligence)("welcome", "return"),
        )
    }

    @Test
    fun `waits for the index even with a file, and fails with the phase if it isn't ready in time`() = runTest {
        val (files, intelligence) = project(notReadyPhase = "Indexing")

        val result = GetDefinition(files, intelligence, readinessTimeout = 5.seconds)("welcome", "greet", file = callerKt)

        assertEquals(GetDefinitionResult.NotReady(Readiness.NotReady("Indexing", 5.seconds)), result)
        assertEquals(emptyList(), intelligence.analysed)
    }

    @Test
    fun `a target in a file Kronstadt can't address, such as Java source or a file outside the project, shows where it is`() = runTest {
        val inJava = Definition.InSource(SourceLocation(root.resolve(legacyJava), range(3, 25, 3, 30)))
        val outside = Definition.InSource(SourceLocation(Path.of("/elsewhere/Gen.kt"), range(9, 4, 9, 9)))
        val (files, intelligence) = project(mapOf(inCaller(4, 19) to listOf(inJava, outside)))

        val result = GetDefinition(files, intelligence)("welcome", "greet")

        assertEquals(
            GetDefinitionResult.Found(
                listOf(
                    FoundDefinition.NotKotlin("hello", legacyJava, 4, "public static String hello() { return \"hi\"; }"),
                    FoundDefinition.OutsideProject("greet", "/elsewhere/Gen.kt", 10),
                ),
            ),
            result,
        )
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `a package prefix is only for Symbol Paths without a file`() = runTest {
        val (files, intelligence) = project()

        assertIs<LookupFailure.InvalidPath>(GetDefinition(files, intelligence)("com.example/welcome", "greet", file = callerKt).lookupFailure())
    }

    @Test
    fun `a name cut short by the end of the at snippet is not a whole identifier`() = runTest {
        val (files, intelligence) = project()

        assertEquals(
            GetDefinitionResult.NameNotExactlyOnce("gre", "welcome", callerKt, emptyList()),
            GetDefinition(files, intelligence)("welcome", "gre", at = "greeter.gre"),
        )
    }
}
