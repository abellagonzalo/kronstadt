package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolCandidate
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/** `kt_find_usages`: Usages of a resolved Symbol, grouped by file and by the Symbol Path that contains each one. */
class FindUsagesTest {
    private val root = Path.of("/project")

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    private fun symbol(name: String, kind: SymbolKind, range: TextRange, nameLine: Int, nameChar: Int, parameterTypes: String? = null, type: String? = null, receiver: String? = null, children: List<CodeSymbol> = emptyList()) =
        CodeSymbol(name, kind, range, range(nameLine, nameChar, nameLine, nameChar + name.length), children, parameterTypes = parameterTypes, type = type, receiver = receiver)

    /** A reference to a name at [line]:[char] (zero-based) of [file]. */
    private fun ref(file: String, line: Int, char: Int) = SourceLocation(root.resolve(file), range(line, char, line, char + 5))

    // A small project: the Greeter, callers in main source, a script, and a test.

    private val greeterKt = "src/main/kotlin/com/example/Greeter.kt"
    private val greeterSource = """
        |package com.example
        |
        |class Greeter {
        |    fun greet(name: String): String = "Hello, ${'$'}name"
        |
        |    fun greetTwice(name: String): String = greet(name) + greet(name)
        |}
        |""".trimMargin()
    private val greet = symbol("greet", SymbolKind.FUNCTION, range(3, 4, 3, 54), 3, 8, parameterTypes = "String", type = "(String): String")
    private val greetTwice = symbol("greetTwice", SymbolKind.FUNCTION, range(5, 4, 5, 70), 5, 8, parameterTypes = "String", type = "(String): String")
    private val greeter = symbol("Greeter", SymbolKind.CLASS, range(2, 0, 6, 1), 2, 6, children = listOf(greet, greetTwice))

    private val callersKt = "src/main/kotlin/com/example/Callers.kt"
    private val callersSource = """
        |package com.example
        |
        |import com.example.Greeter
        |
        |val greeting = Greeter().greet("world")
        |
        |fun greetAll(names: List<String>): List<String> {
        |    fun polite(name: String) = Greeter().greet(name) + "!"
        |    return names.map { Greeter().greet(it) }
        |}
        |""".trimMargin()
    private val greeting = symbol("greeting", SymbolKind.PROPERTY, range(4, 0, 4, 39), 4, 4)

    // kotlin-lsp omits the local `polite` from documentSymbol.
    private val greetAll = symbol("greetAll", SymbolKind.FUNCTION, range(6, 0, 9, 1), 6, 4, parameterTypes = "List<String>", type = "(List<String>): List<String>")

    private val helloKts = "scripts/hello.kts"
    private val helloSource = """
        |import com.example.Greeter
        |
        |println(Greeter().greet("script"))
        |""".trimMargin()

    // Sorts before src/main, so only the main-before-test rule puts it last.
    private val greeterTestKt = "src/integrationTest/kotlin/com/example/GreeterTest.kt"
    private val greeterTestSource = """
        |package com.example
        |
        |class GreeterTest {
        |    fun greets() = check(Greeter().greet("x") == "Hello, x")
        |}
        |""".trimMargin()
    private val greets = symbol("greets", SymbolKind.FUNCTION, range(3, 4, 3, 60), 3, 8)
    private val greeterTest = symbol("GreeterTest", SymbolKind.CLASS, range(2, 0, 4, 1), 2, 6, children = listOf(greets))

    private val greetName = root.resolve(greeterKt) to greet.selectionRange.start
    private val greeterName = root.resolve(greeterKt) to greeter.selectionRange.start

    /** A file `Calls<n>.kt` whose one function, `calls<n>`, calls greet on each of [count] lines (zero-based 2 onwards). */
    private fun calls(n: Int, count: Int): Triple<String, String, CodeSymbol> {
        val source = "package com.example\n\nfun calls$n() {\n" + "    Greeter().greet(\"x\")\n".repeat(count) + "}\n"
        val function = symbol("calls$n", SymbolKind.FUNCTION, range(2, 0, count + 3, 1), 2, 4, parameterTypes = "", type = "()")
        return Triple("src/main/kotlin/com/example/Calls$n.kt", source, function)
    }

    private fun callsRefs(file: String, count: Int) = (0 until count).map { ref(file, it + 3, 14) }

    private fun project(
        references: Map<Pair<Path, Position>, List<SourceLocation>>,
        notReadyPhase: String? = null,
        extra: List<Triple<String, String, CodeSymbol>> = emptyList(),
        searchCap: Int? = null,
    ): Pair<FakeFileStore, FakeCodeIntelligence> {
        val files = FakeFileStore(
            root,
            mapOf(greeterKt to greeterSource, callersKt to callersSource, helloKts to helloSource, greeterTestKt to greeterTestSource) +
                extra.associate { (file, source, _) -> file to source },
        )
        val symbols = mapOf(
            greeterKt to listOf(greeter),
            callersKt to listOf(greeting, greetAll),
            helloKts to emptyList(),
            greeterTestKt to listOf(greeterTest),
        ) + extra.associate { (file, _, function) -> file to listOf(function) }
        val index = symbols.flatMap { (file, roots) ->
            fun all(s: CodeSymbol): List<CodeSymbol> = listOf(s) + s.children.flatMap(::all)
            roots.flatMap(::all).map { SymbolLocation(it.name, it.kind, root.resolve(file), it.selectionRange) }
        }
        val intelligence = FakeCodeIntelligence(
            notReadyPhase = notReadyPhase,
            symbolsByFile = symbols.mapKeys { (file, _) -> root.resolve(file) },
            index = index,
            references = references,
            searchCap = searchCap,
        )
        return files to intelligence
    }

    /** Finds [symbol]'s Usages when the Symbol named at [declaredAt] has [references]. */
    private suspend fun usages(symbol: String, vararg references: SourceLocation, declaredAt: Pair<Path, Position> = greetName): String {
        val (files, intelligence) = project(mapOf(declaredAt to references.toList()))
        return assertIs<FindUsagesResult.Found>(FindUsages(files, intelligence)(symbol)).text
    }

    @Test
    fun `lists Usages under the Symbol Path that contains them, after a summary line, each source line once`() = runTest {
        assertEquals(
            """
            |2 usages in 1 file
            |src/main/kotlin/com/example/Greeter.kt
            |  greetTwice
            |    L6: fun greetTwice(name: String): String = greet(name) + greet(name)
            """.trimMargin(),
            usages("Greeter/greet", ref(greeterKt, 5, 43), ref(greeterKt, 5, 57)),
        )
    }

    @Test
    fun `attributes Usages in lambdas and local functions to the nearest addressable Symbol`() = runTest {
        assertEquals(
            """
            |2 usages in 1 file
            |src/main/kotlin/com/example/Callers.kt
            |  greetAll
            |    L8: fun polite(name: String) = Greeter().greet(name) + "!"
            |    L9: return names.map { Greeter().greet(it) }
            """.trimMargin(),
            usages("Greeter/greet", ref(callersKt, 7, 41), ref(callersKt, 8, 34)),
        )
    }

    @Test
    fun `lists Usages outside any Symbol under (file level)`() = runTest {
        assertEquals(
            """
            |1 usage in 1 file
            |scripts/hello.kts
            |  (file level)
            |    L3: println(Greeter().greet("script"))
            """.trimMargin(),
            usages("Greeter/greet", ref(helloKts, 2, 18)),
        )
    }

    @Test
    fun `counts imports in the summary without listing them, nor files that only import the Symbol`() = runTest {
        assertEquals(
            """
            |1 usage in 1 file (+2 imports)
            |src/main/kotlin/com/example/Callers.kt
            |  greeting
            |    L5: val greeting = Greeter().greet("world")
            """.trimMargin(),
            usages("Greeter", ref(callersKt, 2, 19), ref(callersKt, 4, 15), ref(helloKts, 0, 19), declaredAt = greeterName),
        )
    }

    @Test
    fun `lists main files before test files, each by path, and Usages in line order`() = runTest {
        assertEquals(
            """
            |5 usages in 4 files
            |scripts/hello.kts
            |  (file level)
            |    L3: println(Greeter().greet("script"))
            |src/main/kotlin/com/example/Callers.kt
            |  greeting
            |    L5: val greeting = Greeter().greet("world")
            |  greetAll
            |    L9: return names.map { Greeter().greet(it) }
            |src/main/kotlin/com/example/Greeter.kt
            |  greetTwice
            |    L6: fun greetTwice(name: String): String = greet(name) + greet(name)
            |src/integrationTest/kotlin/com/example/GreeterTest.kt
            |  greets
            |    L4: fun greets() = check(Greeter().greet("x") == "Hello, x")
            """.trimMargin(),
            usages(
                "Greeter/greet",
                ref(greeterTestKt, 3, 35),
                ref(callersKt, 8, 34),
                ref(greeterKt, 5, 43),
                ref(callersKt, 4, 25),
                ref(helloKts, 2, 18),
            ),
        )
    }

    @Test
    fun `shows at most 100 Usages, cut at a file boundary, and names the files left out`() = runTest {
        val files = (1..3).map { calls(it, 40) }
        val (store, intelligence) = project(mapOf(greetName to files.flatMap { (file, _, _) -> callsRefs(file, 40) }), extra = files)

        val text = assertIs<FindUsagesResult.Found>(FindUsages(store, intelligence)("Greeter/greet")).text

        assertEquals("120 usages in 3 files", text.lines().first())
        assertEquals(listOf("src/main/kotlin/com/example/Calls1.kt", "src/main/kotlin/com/example/Calls2.kt"), text.lines().filter { it.startsWith("src/") })
        assertEquals(80, text.lines().count { it.startsWith("    L") })
        assertEquals("… 40 more usages in 1 file: src/main/kotlin/com/example/Calls3.kt", text.lines().last())
    }

    @Test
    fun `shows a first file of more than 100 Usages whole`() = runTest {
        val files = listOf(calls(1, 120), calls(2, 1))
        val (store, intelligence) = project(mapOf(greetName to callsRefs(files[0].first, 120) + callsRefs(files[1].first, 1)), extra = files)

        val text = assertIs<FindUsagesResult.Found>(FindUsages(store, intelligence)("Greeter/greet")).text

        assertEquals(120, text.lines().count { it.startsWith("    L") })
        assertEquals("… 1 more usage in 1 file: src/main/kotlin/com/example/Calls2.kt", text.lines().last())
    }

    @Test
    fun `says how many files were searched when there are no Usages`() = runTest {
        val (files, intelligence) = project(emptyMap())

        val result = FindUsages(files, intelligence)("/Greeter/greetTwice")

        assertEquals(FindUsagesResult.NoUsages("greetTwice", searchedFiles = 4), result)
    }

    @Test
    fun `a file filter keeps only that file's Usages and imports`() = runTest {
        val (files, intelligence) = project(
            mapOf(greeterName to listOf(ref(callersKt, 2, 19), ref(callersKt, 4, 15), ref(helloKts, 0, 19), ref(helloKts, 2, 8))),
        )

        val result = FindUsages(files, intelligence)("Greeter", file = callersKt)

        assertEquals(
            """
            |1 usage in 1 file (+1 import)
            |src/main/kotlin/com/example/Callers.kt
            |  greeting
            |    L5: val greeting = Greeter().greet("world")
            """.trimMargin(),
            assertIs<FindUsagesResult.Found>(result).text,
        )
    }

    @Test
    fun `with a file filter, searched one file`() = runTest {
        val (files, intelligence) = project(mapOf(greetName to listOf(ref(greeterKt, 5, 43))))

        assertEquals(FindUsagesResult.NoUsages("greet", searchedFiles = 1), FindUsages(files, intelligence)("greet", file = callersKt))
    }

    @Test
    fun `resolves the Symbol in declaredIn, so a name the index may have cut off is found`() = runTest {
        // A full page that ends in 'greet': project-wide, the index may have left a declaration out.
        val (files, intelligence) = project(mapOf(greetName to listOf(ref(callersKt, 4, 25))), searchCap = 1)
        assertEquals(
            FindUsagesResult.LookupFailed(LookupFailure.TooManyDeclarations("greet", "greet")),
            FindUsages(files, intelligence)("greet"),
        )

        val result = FindUsages(files, intelligence)("greet", declaredIn = greeterKt)

        assertEquals(
            """
        |1 usage in 1 file
        |src/main/kotlin/com/example/Callers.kt
        |  greeting
        |    L5: val greeting = Greeter().greet("world")
        """.trimMargin(),
            assertIs<FindUsagesResult.Found>(result).text,
        )
    }

    @Test
    fun `with declaredIn and a file filter, resolves in the one and lists only the Usages in the other`() = runTest {
        val (files, intelligence) = project(
            mapOf(greetName to listOf(ref(greeterKt, 5, 43), ref(callersKt, 4, 25), ref(helloKts, 2, 18))),
            searchCap = 1,
        )

        val result = FindUsages(files, intelligence)("greet", file = helloKts, declaredIn = greeterKt)

        assertEquals(
            """
        |1 usage in 1 file
        |scripts/hello.kts
        |  (file level)
        |    L3: println(Greeter().greet("script"))
        """.trimMargin(),
            assertIs<FindUsagesResult.Found>(result).text,
        )
    }

    @Test
    fun `a path declaredIn doesn't declare is not found, with close names from that file`() = runTest {
        val (files, intelligence) = project(emptyMap())

        val result = FindUsages(files, intelligence)("Greeter/gret", declaredIn = greeterKt)

        assertEquals(
            FindUsagesResult.LookupFailed(LookupFailure.NotFound("Greeter/gret", greeterKt, listOf(SymbolCandidate("Greeter/greet", greeterKt)))),
            result,
        )
    }

    @Test
    fun `a file filter or declaredIn the lookup module rejects is reported as its lookup failure, naming the argument`() = runTest {
        val (files, intelligence) = project(emptyMap())
        val findUsages = FindUsages(files, intelligence)
        val rejected = listOf(
            "README.md" to LookupFailure.NotKotlin("README.md"),
            "../Outside.kt" to LookupFailure.OutsideProject("../Outside.kt"),
            "src/main/kotlin/Missing.kt" to LookupFailure.FileNotFound("src/main/kotlin/Missing.kt"),
        )

        rejected.forEach { (path, failure) ->
            assertEquals(FindUsagesResult.LookupFailed(failure, FindUsagesResult.FileArgument.FILE), findUsages("greet", file = path))
            assertEquals(FindUsagesResult.LookupFailed(failure, FindUsagesResult.FileArgument.DECLARED_IN), findUsages("greet", declaredIn = path))
        }
    }

    @Test
    fun `waits for the index, and fails with its phase if it stays incomplete`() = runTest {
        val (files, intelligence) = project(emptyMap(), notReadyPhase = "Indexing")

        val result = FindUsages(files, intelligence, readinessTimeout = 5.seconds)("greet")

        assertEquals(FindUsagesResult.NotReady(Readiness.NotReady("Indexing", 5.seconds)), result)
    }

    @Test
    fun `reports an ambiguous Symbol Path with each candidate and its file`() = runTest {
        val otherKt = "src/main/kotlin/com/example/Other.kt"
        val otherGreet = symbol("greet", SymbolKind.FUNCTION, range(2, 0, 2, 26), 2, 4, parameterTypes = "", type = "(): String")
        val (files, intelligence) = project(emptyMap(), extra = listOf(Triple(otherKt, "package com.example\n\nfun greet(): String = \"hi\"\n", otherGreet)))

        val result = FindUsages(files, intelligence)("greet")

        assertEquals(FindUsagesResult.LookupFailed(LookupFailure.Ambiguous("greet", null, listOf(SymbolCandidate("greet", greeterKt), SymbolCandidate("greet", otherKt)))), result)
    }

    @Test
    fun `reports an unknown Symbol Path with close names`() = runTest {
        val (files, intelligence) = project(emptyMap())

        val result = FindUsages(files, intelligence)("Greeter/gret")

        assertEquals(FindUsagesResult.LookupFailed(LookupFailure.NotFound("Greeter/gret", null, listOf(SymbolCandidate("Greeter/greet", greeterKt)))), result)
    }

    @Test
    fun `cuts a long source line to 120 characters`() = runTest {
        val longKt = "src/main/kotlin/com/example/Long.kt"
        val line = "val long = Greeter().greet(\"${"x".repeat(150)}\")"
        val long = symbol("long", SymbolKind.PROPERTY, range(2, 0, 2, line.length), 2, 4)
        val (files, intelligence) = project(
            mapOf(greetName to listOf(ref(longKt, 2, 21))),
            extra = listOf(Triple(longKt, "package com.example\n\n    $line\n", long.copy(range = range(2, 4, 2, 4 + line.length)))),
        )

        val text = assertIs<FindUsagesResult.Found>(FindUsages(files, intelligence)("greet")).text

        assertEquals("    L3: " + line.take(119) + "…", text.lines().last())
    }

    @Test
    fun `ignores Usages the language server reports outside the project`() = runTest {
        val outside = SourceLocation(Path.of("/elsewhere/Greeter.kt"), range(0, 0, 0, 5))
        val (files, intelligence) = project(mapOf(greetName to listOf(outside, ref(greeterKt, 5, 43))))

        val text = assertIs<FindUsagesResult.Found>(FindUsages(files, intelligence)("greet")).text

        assertEquals("1 usage in 1 file", text.lines().first())
    }

    @Test
    fun `closes every document it opened (ADR 0003)`() = runTest {
        val (files, intelligence) = project(mapOf(greetName to listOf(ref(greeterKt, 5, 43), ref(callersKt, 4, 25), ref(helloKts, 0, 19))))

        FindUsages(files, intelligence)("greet")

        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `says there are no Usages but imports when imports are all there is`() = runTest {
        val (files, intelligence) = project(mapOf(greeterName to listOf(ref(callersKt, 2, 19), ref(helloKts, 0, 19))))

        assertEquals(FindUsagesResult.NoUsages("Greeter", searchedFiles = 4, imports = 2), FindUsages(files, intelligence)("Greeter"))
    }
}
