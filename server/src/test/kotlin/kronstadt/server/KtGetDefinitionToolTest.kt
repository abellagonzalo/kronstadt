package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
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
    }

    private val tools = ToolHarness(root, GreetIntelligence)

    /** Calls kt_get_definition on `greet` in A.kt for `n` at [at]; whether it failed, and its text. */
    private suspend fun at(at: String): Pair<Boolean, String> =
        tools.call("kt_get_definition", """{"file": "A.kt", "symbol": "greet", "name": "n", "at": "$at"}""")

    @Test
    fun `an at snippet that starts mid-line and matches only with different indentation names the line its indents describe`() = runTest {
        assertEquals(
            true to "'= 1 +\n    2' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if indentation is " +
                "ignored: the file indents line 5 by 8 spaces, 'at' by 4 spaces. Copy 'at' from kt_read_symbol output.",
            at("= 1 +\\n    2"),
        )
    }

    @Test
    fun `an indented at snippet that matches only with different indentation describes the line it starts on`() = runTest {
        assertEquals(
            true to "'  val n = 1 +\n      2' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if " +
                "indentation is ignored: the file indents it by 4 spaces, 'at' by 2 spaces. Copy 'at' from kt_read_symbol output.",
            at("  val n = 1 +\\n      2"),
        )
    }
}

/** Always ready; every file has one function, `greet`, on lines 3 to 7, and no definitions are ever looked up. */
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
    })

    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
