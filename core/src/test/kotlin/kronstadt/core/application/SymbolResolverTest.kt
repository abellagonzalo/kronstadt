package kronstadt.core.application

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

/** The lookup module every tool goes through: parsing a Symbol Path, checking its file, and resolving it. */
class SymbolResolverTest {
    private val root = Path.of("/project")

    private fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int) =
        TextRange(Position(startLine, startChar), Position(endLine, endChar))

    private fun function(name: String, line: Int) =
        CodeSymbol(name, SymbolKind.FUNCTION, range(line, 0, line, 20), range(line, 4, line, 4 + name.length), parameterTypes = "", type = "(): Int")

    private val aKt = "src/com/a/Greeter.kt"
    private val aSource = "package com.a\n\nfun greet(): Int = 1\n"
    private val bKt = "src/com/b/Greeter.kt"
    private val bSource = "package com.b\n\nfun greet(): Int = 2\n"
    private val greet = function("greet", 2)

    private val files = FakeFileStore(root, mapOf(aKt to aSource, bKt to bSource, "README.md" to "# readme\n"))

    private fun intelligence(notReadyPhase: String? = null) = FakeCodeIntelligence(
        index = listOf(
            SymbolLocation("greet", SymbolKind.FUNCTION, root.resolve(aKt), greet.range),
            SymbolLocation("greet", SymbolKind.FUNCTION, root.resolve(bKt), greet.range),
        ),
        symbolsByFile = mapOf(root.resolve(aKt) to listOf(greet), root.resolve(bKt) to listOf(greet)),
        notReadyPhase = notReadyPhase,
    )

    private fun parsed(file: String?, symbol: String) = assertIs<Outcome.Ok<ParsedLookup>>(SymbolResolver.parse(file, symbol)).value

    private suspend fun resolve(file: String?, symbol: String, intelligence: FakeCodeIntelligence = intelligence()) =
        intelligence.session { SymbolResolver(files, intelligence).resolve(it, parsed(file, symbol)) }

    @Test
    fun `parsing keeps the path as passed, with its file`() {
        val parsed = parsed(aKt, "greet")
        assertEquals("greet", parsed.symbol)
        assertEquals(aKt, parsed.file)
        assertEquals("com.a", parsed(null, "com.a/greet").path.packageName)
    }

    @Test
    fun `parsing rejects malformed paths, and a package prefix with a file`() {
        assertIs<LookupFailure.InvalidPath>(assertIs<Outcome.Failed>(SymbolResolver.parse(null, "(Int)x")).failure)
        assertEquals(
            Outcome.Failed(LookupFailure.InvalidPath("com.a/greet", "a package prefix is only for paths without a file; drop it or the file")),
            SymbolResolver.parse(aKt, "com.a/greet"),
        )
    }

    @Test
    fun `a file is checked in order - inside the project, then Kotlin, then existing`() = runTest {
        assertEquals(Outcome.Failed(LookupFailure.OutsideProject("../other/README.md")), files.classify("../other/README.md"))
        assertEquals(Outcome.Failed(LookupFailure.NotKotlin("README.md")), files.classify("README.md"))
        assertEquals(Outcome.Failed(LookupFailure.FileNotFound("src/Missing.kt")), files.classify("src/Missing.kt"))
        assertEquals(Outcome.Ok(root.resolve(aKt)), files.classify(aKt))
    }

    @Test
    fun `resolving in a file reports the file's checks as lookup failures`() = runTest {
        assertEquals(Outcome.Failed(LookupFailure.OutsideProject("../other/B.kt")), resolve("../other/B.kt", "greet"))
        assertEquals(Outcome.Failed(LookupFailure.NotKotlin("README.md")), resolve("README.md", "greet"))
        assertEquals(Outcome.Failed(LookupFailure.FileNotFound("src/Missing.kt")), resolve("src/Missing.kt", "greet"))
    }

    @Test
    fun `in a file, Ambiguous and NotFound name the file as shown, and project-wide they name none`() = runTest {
        assertEquals(Outcome.Failed(LookupFailure.NotFound("gret", aKt, listOf(SymbolCandidate("greet", aKt)))), resolve(aKt, "gret"))
        assertEquals(
            Outcome.Failed(LookupFailure.Ambiguous("greet", null, listOf(SymbolCandidate("greet", aKt), SymbolCandidate("greet", bKt)))),
            resolve(null, "greet"),
        )
        assertEquals(root.resolve(bKt), assertIs<Outcome.Ok<SymbolResolver.Found>>(resolve(null, "com.b/greet")).value.file)
    }

    @Test
    fun `the lookup module never waits for the index - the tool does`() = runTest {
        val intelligence = intelligence(notReadyPhase = "Indexing")

        resolve(null, "com.a/greet", intelligence)
        resolve(aKt, "greet", intelligence)

        assertEquals(0, intelligence.readinessAwaits)
    }
}
