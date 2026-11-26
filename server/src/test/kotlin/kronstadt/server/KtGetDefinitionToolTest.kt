package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.port.AnalysisSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** The kt_get_definition tool's arguments as an agent sends them, through to the text it answers. */
class KtGetDefinitionToolTest {
    private val root: Path = Files.createTempDirectory("kt-get-definition-tool").toRealPath()

    init {
        root.resolve("A.kt").writeText("package a\n\nfun greet(): Int {\n    val n = 1 +\n        2\n    return n\n}\n")
        root.resolve("B.kt").writeText("package a\n\nfun greet(): Int {\n    val n = 1 +\n        2\n    return m\n}\n")
    }

    private val tools = ToolHarness(root, GreetIntelligence)

    @Test
    fun `a name that resolves to nothing quotes the compiler's diagnostic on it and is not an error`() = runTest {
        assertEquals(
            false to "'m' on L6 of greet resolves to no declaration (a keyword, a literal or an unresolved reference). " +
                "Compiler: UNRESOLVED_REFERENCE: Unresolved reference 'm'.",
            tools.call("kt_get_definition", """{"file": "B.kt", "symbol": "greet", "name": "m"}"""),
        )
    }

    @Test
    fun `an at snippet with LF line breaks finds the name in a CRLF file`() = runTest {
        root.resolve("A.kt").writeText("package a\r\n\r\nfun greet(): Int {\r\n    val n = 1 +\r\n        2\r\n    return n\r\n}\r\n")
        val declaration = Definition.InSource(SourceLocation(root.resolve("A.kt"), TextRange(Position(3, 8), Position(3, 9))))
        // Only the `n` after `return`, on line 6, resolves.
        val intelligence = object : UnstubbedCodeIntelligence() {
            override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = GreetIntelligence.session { greet ->
                block(object : UnstubbedAnalysisSession() {
                    override suspend fun documentSymbols(file: Path, document: TextDocument) = greet.documentSymbols(file, document)
                    override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> =
                        if (position == Position(5, 11)) listOf(declaration) else emptyList()
                })
            }

            override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
        }

        assertEquals(
            false to "// n is local to greet in A.kt, not addressable; declared at L4\nval n = 1 +",
            ToolHarness(root, intelligence).call("kt_get_definition", """{"file": "A.kt", "symbol": "greet", "name": "n", "at": "2\n    return n"}"""),
        )
    }
}

/**
 * Always ready; every file has one function, `greet`, on lines 3 to 7. No name resolves to a declaration, and the
 * compiler reports only B.kt's `m`, on line 6, as an unresolved reference.
 */
private object GreetIntelligence : UnstubbedCodeIntelligence() {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : UnstubbedAnalysisSession() {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> = listOf(
            CodeSymbol(
                "greet",
                SymbolKind.FUNCTION,
                TextRange(Position(2, 0), Position(6, 1)),
                TextRange(Position(2, 4), Position(2, 9)),
                emptyList(),
                parameterTypes = "",
                type = "(): Int",
            ),
        )

        override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> = emptyList()

        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> =
            if (file.fileName.toString() == "B.kt") {
                listOf(Diagnostic(TextRange(Position(5, 11), Position(5, 12)), Severity.ERROR, "Unresolved reference 'm'.", "UNRESOLVED_REFERENCE"))
            } else {
                emptyList()
            }
    })

    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
