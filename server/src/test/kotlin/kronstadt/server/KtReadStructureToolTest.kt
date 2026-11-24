package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kronstadt.core.application.Edit
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindUsages
import kronstadt.core.application.GetDefinition
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.RenameSymbol
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.workspace.LocalFileStore
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration

/** The kt_read_structure tool's arguments as an agent sends them: the target file is `file`, as in every other tool. */
class KtReadStructureToolTest {
    private val root: Path = Files.createTempDirectory("kt-read-structure-tool").toRealPath()
    private val a = root.resolve("A.kt").apply { writeText("package a\n\nfun greet(): Int = 1\n") }

    private val server = Server(
        Implementation(name = "kronstadt-test", version = "0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).also { server ->
        val files = LocalFileStore(root)
        val intelligence = OneFunctionIntelligence(a)
        KronstadtTools(
            ReadStructure(files, intelligence),
            FindSymbol(files, intelligence),
            ReadSymbol(files, intelligence),
            FindUsages(files, intelligence),
            GetDefinition(files, intelligence),
            Edit(files, intelligence),
            RenameSymbol(files, intelligence),
        ).registerOn(server)
    }

    private val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ClientConnection::class.java)) { _, method, _ ->
        error("unexpected call to ClientConnection.${method.name}")
    } as ClientConnection

    private suspend fun call(arguments: String): Pair<Boolean, String> {
        val request = CallToolRequest(CallToolRequestParams("kt_read_structure", Json.parseToJsonElement(arguments) as JsonObject))
        val result = checkNotNull(server.tools["kt_read_structure"]).handler(connection, request)
        return (result.isError == true) to result.content.joinToString("\n") { (it as TextContent).text }
    }

    @Test
    fun `the schema names the target file 'file', requires it, and takes nothing else`() {
        val schema = checkNotNull(server.tools["kt_read_structure"]).tool.inputSchema

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

/** Always ready; knows one function, `greet`, in [a]. */
private class OneFunctionIntelligence(private val a: Path) : CodeIntelligence {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : AnalysisSession {
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

        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> = emptyList()
        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> = emptyList()
        override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> = emptyList()
        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation =
            error("unexpected prepareRename")
        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename =
            error("unexpected rename")
        override suspend fun organizeImports(file: Path, document: TextDocument): TextDocument? = null
        override suspend fun writtenBack(file: Path, document: TextDocument) = Unit
    })

    override suspend fun workspaceSymbols(query: String): SymbolSearch = SymbolSearch(emptyList())
    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
