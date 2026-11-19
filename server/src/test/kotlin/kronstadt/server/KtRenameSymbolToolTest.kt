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
import kronstadt.core.domain.FileChange
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.TextRange
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
import kotlin.time.Duration

/** The kt_rename_symbol tool's arguments as an agent sends them, through to its text and the files on disk. */
class KtRenameSymbolToolTest {
    private val root: Path = Files.createTempDirectory("kt-rename-tool").toRealPath()
    private val a = root.resolve("A.kt").apply { writeText("package a\n\nfun greet(): Int = 1\n") }
    private val b = root.resolve("B.kt").apply { writeText("package b\n\nimport a.greet\n\nval x = greet()\n") }

    private val server = Server(
        Implementation(name = "kronstadt-test", version = "0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).also { server ->
        val files = LocalFileStore(root)
        val intelligence = RenamingIntelligence(a, b)
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

    private suspend fun call(tool: String, arguments: String): Pair<Boolean, String> {
        val request = CallToolRequest(CallToolRequestParams(tool, Json.parseToJsonElement(arguments) as JsonObject))
        val result = checkNotNull(server.tools[tool]).handler(connection, request)
        return (result.isError == true) to result.content.joinToString("\n") { (it as TextContent).text }
    }

    @Test
    fun `a rename reports the new path and each changed file with its Usages and imports`() = runTest {
        val (failed, text) = call("kt_rename_symbol", """{"symbol": "greet", "newName": "hello", "file": "A.kt"}""")

        assertEquals(false, failed, text)
        assertEquals(
            "Renamed greet to hello: 1 usage in 1 file (+1 import), and the declaration. New path: hello\n" +
                "  A.kt: declaration\n" +
                "  B.kt: 1 usage, 1 import",
            text,
        )
        assertEquals("package a\n\nfun hello(): Int = 1\n", a.readText())
        assertEquals("package b\n\nimport a.hello\n\nval x = hello()\n", b.readText())
    }

    @Test
    fun `a new name that isn't an identifier is an error naming it`() = runTest {
        val (failed, text) = call("kt_rename_symbol", """{"symbol": "greet", "newName": "say hi", "file": "A.kt"}""")

        assertEquals(true, failed)
        assertEquals("'say hi' is not a Kotlin identifier; pass a plain name, or one in backticks. Nothing written.", text)
    }

    @Test
    fun `a rename can't be an Operation of a kt_edit batch`() = runTest {
        val (failed, text) = call("kt_edit", """{"operations": [{"type": "rename", "symbol": "greet", "file": "A.kt"}]}""")

        assertEquals(true, failed)
        assertEquals(
            "Operation 0: unsupported type 'rename'; use replace, insert_before, insert_after, delete or edit_in, and " +
                "kt_rename_symbol for renames, on its own. Nothing written.",
            text,
        )
    }
}

/** Always ready; knows `greet` in [a] (or `hello` once renamed), and renames it there and in [b]. */
private class RenamingIntelligence(private val a: Path, private val b: Path) : CodeIntelligence {
    private fun name(line: Int, char: Int, length: Int) = TextRange(Position(line, char), Position(line, char + length))

    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : AnalysisSession {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> {
            if (file != a) return emptyList()
            val function = if ("greet" in document.text) "greet" else "hello"
            return listOf(CodeSymbol(function, SymbolKind.FUNCTION, TextRange(Position(2, 0), Position(2, 20)), name(2, 4, 5), detail = "(): Int"))
        }

        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> = emptyList()
        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> = emptyList()
        override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> = emptyList()
        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation =
            RenamePreparation.Renameable(name(2, 4, 5), "greet")

        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename = Rename.Changes(
            listOf(
                FileChange.Edited(b, listOf(TextEdit(name(2, 9, 5), newName), TextEdit(name(4, 8, 5), newName))),
                FileChange.Edited(a, listOf(TextEdit(name(2, 4, 5), newName))),
            ),
        )

        override suspend fun organizeImports(file: Path, document: TextDocument): TextDocument? = null

        override suspend fun writtenBack(file: Path, document: TextDocument) = Unit
    })

    override suspend fun workspaceSymbols(query: String): SymbolSearch = SymbolSearch(emptyList())
    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
