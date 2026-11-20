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
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.workspace.LocalFileStore
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.time.Duration

/** The kt_edit tool's arguments as an agent sends them, through to the file on disk. */
class KtEditToolTest {
    private val root: Path = Files.createTempDirectory("kt-edit-tool").toRealPath()
    private val file = root.resolve("A.kt").apply { writeText("package a\n\nfun greet(): Int = 1\n") }

    /** What organize-imports answers, by exact text; filled by a test before it calls the tool. */
    private val organized = mutableMapOf<String, String>()

    /** Queries the index answers possibly incompletely; filled by a test before it calls the tool. */
    private val cutOff = mutableSetOf<String>()

    /** The Symbols every file has; filled by a test before it calls the tool. */
    private val symbols = mutableListOf<CodeSymbol>()

    private val server = Server(
        Implementation(name = "kronstadt-test", version = "0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).also { server ->
        val files = LocalFileStore(root)
        val intelligence = EmptyIntelligence(organized, cutOff, symbols)
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

    @Test
    fun `an edit_in whose old starts inside a line's indentation fails, naming the line, and writes nothing`() = runTest {
        val source = "package a\n\nfun greet(): Int {\n    return 1\n}\n"
        file.writeText(source)
        symbols += CodeSymbol(
            "greet",
            SymbolKind.FUNCTION,
            TextRange(Position(2, 0), Position(4, 1)),
            TextRange(Position(2, 4), Position(2, 9)),
            emptyList(),
            parameterTypes = "",
            type = "(): Int",
        )

        val (inFile, inFileText) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "  return 1", "new": "  return 2"}]}""")
        val (inSymbol, inSymbolText) =
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "symbol": "greet", "old": "  return 1", "new": "  return 2"}]}""")

        assertEquals(true, inFile)
        assertEquals(
            "Operation 0: 'old' starts inside line 4's indentation in A.kt; start it at the beginning of the line or after " +
                "the indentation. Nothing written.",
            inFileText,
        )
        assertEquals(true, inSymbol)
        assertEquals(
            "Operation 0: 'old' starts inside line 4's indentation in greet in A.kt; start it at the beginning of the line " +
                "or after the indentation. Nothing written.",
            inSymbolText,
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `an insert whose code has no non-blank line fails, naming its Operation's type, writing nothing`() = runTest {
        val before = file.readText()

        val (failedBefore, beforeText) = ktEdit("""{"operations": [{"type": "insert_before", "file": "A.kt", "symbol": "greet", "code": ""}]}""")
        val (failedAfter, afterText) = ktEdit(
            """{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= 2"}, """ +
                """{"type": "insert_after", "file": "A.kt", "symbol": "greet", "code": " \n\t\n"}]}""",
        )

        assertEquals(true, failedBefore)
        assertEquals("Operation 0: 'insert_before' needs code with at least one non-blank line. Nothing written.", beforeText)
        assertEquals(true, failedAfter)
        assertEquals("Operation 1: 'insert_after' needs code with at least one non-blank line. Nothing written.", afterText)
        assertEquals(before, file.readText())
    }

    @Test
    fun `a Symbol Path without a file fails, asking for the file, when the index may not list every declaration of its name`() = runTest {
        cutOff += "greet"
        val before = file.readText()

        val (failed, text) = ktEdit("""{"operations": [{"type": "replace", "symbol": "greet", "code": "fun greet(): Int = 2"}]}""")

        assertEquals(true, failed)
        assertEquals(
            "Operation 0: 'greet' can't be resolved without a file: so many declarations are named 'greet', dependencies' " +
                "included, that the project index stops at a maximum number of results and may not list them all; pass its " +
                "file. Nothing written.",
            text,
        )
        assertEquals(before, file.readText())
    }

    @Test
    fun `the schema offers create, and a create writes the new file, its directories with it, and says it was created`() = runTest {
        val schema = checkNotNull(server.tools["kt_edit"]).tool.inputSchema.properties.toString()
        assertTrue("\"create\"" in schema, schema)

        val (failed, text) = ktEdit(
            """{"operations": [{"type": "create", "file": "src/b/B.kt", "code": "package b\r\n\r\nclass B\r\n"}]}""",
        )

        assertEquals(false, failed, text)
        assertEquals("Created src/b/B.kt", text)
        assertEquals("package b\n\nclass B\n", root.resolve("src/b/B.kt").readText())
    }

    @Test
    fun `a create needs a file and code, takes no symbol, and fails on a file that exists, writing nothing`() = runTest {
        val before = file.readText()

        val (_, noFile) = ktEdit("""{"operations": [{"type": "create", "code": "class B"}]}""")
        val (_, noCode) = ktEdit("""{"operations": [{"type": "create", "file": "B.kt"}]}""")
        val (_, withSymbol) = ktEdit("""{"operations": [{"type": "create", "file": "B.kt", "symbol": "B", "code": "class B"}]}""")
        val (failed, exists) = ktEdit("""{"operations": [{"type": "create", "file": "A.kt", "code": "class B"}]}""")

        assertEquals("Operation 0: missing 'file', which 'create' needs. Nothing written.", noFile)
        assertEquals("Operation 0: missing 'code', which 'create' needs. Nothing written.", noCode)
        assertEquals("Operation 0: 'create' takes no 'symbol', only the new 'file' and its 'code'. Nothing written.", withSymbol)
        assertEquals(true, failed)
        assertEquals("Operation 0: A.kt already exists; change it with the other Operations, or edit_in on the whole file. Nothing written.", exists)
        assertEquals(before, file.readText())
        assertEquals(listOf("A.kt"), root.listDirectoryEntries().map { it.name })
    }
}

/**
 * Always ready, and knows next to nothing: every file has [symbols], and there are no diagnostics, references or
 * definitions, so every Edit validates.
 * Organize-imports answers [organized] for the exact text, else nothing. A search for a query in [cutOff] may miss
 * symbols named exactly like it, as when the index stops at its maximum.
 */
private class EmptyIntelligence(
    private val organized: Map<String, String>,
    private val cutOff: Set<String>,
    private val symbols: List<CodeSymbol>,
) : CodeIntelligence {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : AnalysisSession {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> = symbols
        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> = emptyList()
        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> = emptyList()
        override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> = emptyList()
        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position) = RenamePreparation.Refused("unsupported")
        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String) = Rename.Refused("unsupported")
        override suspend fun organizeImports(file: Path, document: TextDocument): TextDocument? = organized[document.text]?.let(::TextDocument)
        override suspend fun writtenBack(file: Path, document: TextDocument) = Unit
    })

    override suspend fun workspaceSymbols(query: String): SymbolSearch =
        SymbolSearch(emptyList(), if (query in cutOff) SymbolSearch.Completeness.INCOMPLETE else SymbolSearch.Completeness.COMPLETE)
    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
