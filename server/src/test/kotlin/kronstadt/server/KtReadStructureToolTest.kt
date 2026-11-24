package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.port.AnalysisSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** The kt_read_structure tool's arguments as an agent sends them: the target file is `file`, as in every other tool. */
class KtReadStructureToolTest {
    private val root: Path = Files.createTempDirectory("kt-read-structure-tool").toRealPath()
    private val a = root.resolve("A.kt").apply { writeText("package a\n\nfun greet(): Int = 1\n") }

    private val tools = ToolHarness(root, OneFunctionIntelligence(a))

    private suspend fun call(arguments: String): Pair<Boolean, String> = tools.call("kt_read_structure", arguments)

    @Test
    fun `the schema names the target file 'file', requires it, and takes nothing else`() {
        val schema = tools.tool("kt_read_structure").inputSchema

        assertEquals(listOf("file"), schema.required)
        assertEquals(setOf("file"), schema.properties?.keys)
    }

    @Test
    fun `a call with file returns the structure under a header line naming the file`() = runTest {
        val (failed, text) = call("""{"file": "A.kt"}""")

        assertFalse(failed, text)
        assertEquals("// A.kt\nfun greet  (): Int  // L3-3", text)
    }

    @Test
    fun `the retired XML flag is ignored, whatever its value`() = runTest {
        val plain = call("""{"file": "A.kt"}""")

        assertEquals(plain, call("""{"file": "A.kt", "formatOutputAsXml": true}"""))
        assertEquals(plain, call("""{"file": "A.kt", "formatOutputAsXml": false}"""))
    }

    @Test
    fun `a call without file is an error naming file`() = runTest {
        val (failed, text) = call("""{"filePath": "A.kt"}""")

        assertEquals(true, failed)
        assertEquals("Missing required argument: file", text)
    }
}

/** Knows one function, `greet`, in [a]. */
private class OneFunctionIntelligence(private val a: Path) : UnstubbedCodeIntelligence() {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : UnstubbedAnalysisSession() {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> =
            if (file != a) emptyList()
            else listOf(
                CodeSymbol(
                    "greet",
                    SymbolKind.FUNCTION,
                    TextRange(Position(2, 0), Position(2, 20)),
                    TextRange(Position(2, 4), Position(2, 9)),
                    parameterTypes = "",
                    type = "(): Int",
                ),
            )
    })
}
