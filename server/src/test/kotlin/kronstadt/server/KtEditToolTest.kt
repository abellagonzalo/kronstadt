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
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.workspace.LocalFileStore
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.time.Duration

/** The kt_edit tool's arguments as an agent sends them, through to the file on disk. */
class KtEditToolTest {
    private val root: Path = Files.createTempDirectory("kt-edit-tool").toRealPath()
    private val file = root.resolve("A.kt").apply { writeText("package a\n\nfun greet(): Int = 1\n") }

    /** What organize-imports answers, by exact text; filled by a test before it calls the tool. */
    private val organized = mutableMapOf<String, String>()

    private val server = Server(
        Implementation(name = "kronstadt-test", version = "0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).also { server ->
        val files = LocalFileStore(root)
        val intelligence = EmptyIntelligence(organized)
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

    /** No tool handler here talks back to the client. */
    private val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ClientConnection::class.java)) { _, method, _ ->
        error("unexpected call to ClientConnection.${method.name}")
    } as ClientConnection

    /** Calls kt_edit with [arguments], JSON as an agent writes it; whether it failed, and its text. */
    private suspend fun ktEdit(arguments: String): Pair<Boolean, String> {
        val request = CallToolRequest(CallToolRequestParams("kt_edit", Json.parseToJsonElement(arguments) as JsonObject))
        val result = checkNotNull(server.tools["kt_edit"]).handler(connection, request)
        return (result.isError == true) to result.content.joinToString("\n") { (it as TextContent).text }
    }

    @Test
    fun `an Operation's imports are added to its file`() = runTest {
        val (failed, text) = ktEdit(
            """{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= 2", "imports": ["b.Bravo", "import a.x.Alpha"]}]}""",
        )

        assertEquals(false, failed, text)
        assertEquals("package a\n\nimport a.x.Alpha\nimport b.Bravo\n\nfun greet(): Int = 2\n", file.readText())
    }

    @Test
    fun `the result lists the imports the Edit made unused, which it removed`() = runTest {
        val source = "package a\n\nimport b.Bravo\nimport c.Charlie\nimport d.Delta\n\nfun greet(): Int = Bravo.one + Charlie.two + Delta.three\n"
        val edited = "package a\n\nimport b.Bravo\nimport c.Charlie\nimport d.Delta\n\nfun greet(): Int = 1 + Delta.three\n"
        organized += mapOf(source to source, edited to "package a\n\nimport d.Delta\n\nfun greet(): Int = 1 + Delta.three\n")
        file.writeText(source)

        val (failed, text) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "Bravo.one + Charlie.two", "new": "1"}]}""")

        assertEquals(false, failed, text)
        assertEquals("Edited A.kt\nRemoved imports it made unused:\n  b.Bravo\n  c.Charlie", text)
        assertEquals("package a\n\nimport d.Delta\n\nfun greet(): Int = 1 + Delta.three\n", file.readText())
    }

    @Test
    fun `imports that aren't an array of import directives reject the call, writing nothing`() = runTest {
        val before = file.readText()

        val (notArray, notArrayText) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= 2", "imports": "b.Bravo"}]}""")
        val (notString, notStringText) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= 2", "imports": [1]}]}""")
        val (malformed, malformedText) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= 2", "imports": ["b Bravo"]}]}""")

        assertEquals(true, notArray)
        assertEquals("Operation 0: 'imports' must be an array of strings. Nothing written.", notArrayText)
        assertEquals(true, notString)
        assertEquals("Operation 0: 'imports' must be an array of strings. Nothing written.", notStringText)
        assertEquals(true, malformed)
        assertEquals(
            "Operation 0: 'b Bravo' is not an import directive; give a dotted name, optionally ending in '.*' or 'as Alias', " +
                "e.g. 'kotlin.time.Duration'. Nothing written.",
            malformedText,
        )
        assertEquals(before, file.readText())
        assertNotEquals("", before)
    }
}

/**
 * Always ready, and knows nothing: no Symbols, diagnostics, references or definitions, so every Edit validates.
 * Organize-imports answers [organized] for the exact text, else nothing.
 */
private class EmptyIntelligence(private val organized: Map<String, String>) : CodeIntelligence {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : AnalysisSession {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> = emptyList()
        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> = emptyList()
        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> = emptyList()
        override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> = emptyList()
        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position) = RenamePreparation.Refused("unsupported")
        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String) = Rename.Refused("unsupported")
        override suspend fun organizeImports(file: Path, document: TextDocument): TextDocument? = organized[document.text]?.let(::TextDocument)
        override suspend fun writtenBack(file: Path, document: TextDocument) = Unit
    })

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> = emptyList()
    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
