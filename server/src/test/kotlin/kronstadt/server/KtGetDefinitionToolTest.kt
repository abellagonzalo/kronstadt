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

    /** Calls kt_get_definition on `greet` in A.kt for `n` at [at]; whether it failed, and its text. */
    private suspend fun getDefinitionAt(at: String): Pair<Boolean, String> =
        tools.call("kt_get_definition", """{"file": "A.kt", "symbol": "greet", "name": "n", "at": "$at"}""")

    @Test
    fun `an at snippet that starts mid-line and matches only with different indentation names the line its indents describe`() = runTest {
        assertEquals(
            true to "'= 1 +\n    2' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if indentation is " +
                "ignored: the file indents line 5 by 8 spaces, 'at' by 4 spaces. Copy 'at' from kt_read_symbol output.",
            getDefinitionAt("= 1 +\\n    2"),
        )
    }

    @Test
    fun `an indented at snippet that matches only with different indentation describes the line it starts on`() = runTest {
        assertEquals(
            true to "'  val n = 1 +\n      2' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if " +
                "indentation is ignored: the file indents it by 4 spaces, 'at' by 2 spaces. Copy 'at' from kt_read_symbol output.",
            getDefinitionAt("  val n = 1 +\\n      2"),
        )
    }

    @Test
    fun `a name that doesn't occur in the Symbol is named as a whole identifier`() = runTest {
        assertEquals(
            true to "'m' (as a whole identifier) doesn't occur in greet in A.kt. Use kt_read_symbol to see its source.",
            tools.call("kt_get_definition", """{"file": "A.kt", "symbol": "greet", "name": "m"}"""),
        )
    }

    @Test
    fun `an at snippet that doesn't occur in the Symbol even with indentation ignored points at kt_read_symbol`() = runTest {
        assertEquals(
            true to "'val m' doesn't occur in greet in A.kt. Use kt_read_symbol to see its source.",
            getDefinitionAt("val m"),
        )
    }

    @Test
    fun `an at snippet whose lines are indented differently from each other says only that they differ`() = runTest {
        assertEquals(
            true to "'  val n = 1 +\n    2\n  return n' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if " +
                "indentation is ignored, where its lines are indented differently from the file's. Copy 'at' from kt_read_symbol output.",
            getDefinitionAt("  val n = 1 +\\n    2\\n  return n"),
        )
    }

    @Test
    fun `a name that occurs more than once with no at snippet asks for one and lists the lines`() = runTest {
        assertEquals(
            true to "'n' (as a whole identifier) occurs more than once in greet in A.kt; pass an 'at' snippet that occurs exactly once:\n" +
                "  L4: val n = 1 +\n  L6: return n",
            tools.call("kt_get_definition", """{"file": "A.kt", "symbol": "greet", "name": "n"}"""),
        )
    }

    @Test
    fun `an at snippet that occurs more than once asks for one that occurs once and lists the lines`() = runTest {
        assertEquals(
            true to "' n' occurs more than once in greet in A.kt; pass an 'at' snippet that occurs exactly once:\n" +
                "  L4: val n = 1 +\n  L6: return n",
            getDefinitionAt(" n"),
        )
    }

    @Test
    fun `a name that occurs more than once in its at snippet asks for a snippet in which it occurs once`() = runTest {
        assertEquals(
            true to "'n' (as a whole identifier) occurs more than once in greet in A.kt; pick an 'at' snippet in which it occurs once:\n" +
                "  L4: val n = 1 +\n  L6: return n",
            getDefinitionAt("n = 1 +\\n        2\\n    return n"),
        )
    }

    @Test
    fun `a name that resolves to nothing and has no diagnostic is not an error`() = runTest {
        assertEquals(
            false to "'return' on L6 of greet resolves to no declaration (a keyword, a literal or an unresolved reference).",
            tools.call("kt_get_definition", """{"file": "A.kt", "symbol": "greet", "name": "return"}"""),
        )
    }

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
