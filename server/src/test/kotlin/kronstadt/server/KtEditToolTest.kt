package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.ImportUsage
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.port.AnalysisSession
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
import kotlin.time.Duration.Companion.seconds

/** The kt_edit tool's arguments as an agent sends them, through to the file on disk. */
class KtEditToolTest {
    private val root: Path = Files.createTempDirectory("kt-edit-tool").toRealPath()
    private val file = root.resolve("A.kt").apply { writeText("package a\n\nfun greet(): Int = 1\n") }

    /** The Symbols every file has; filled by a test before it calls the tool. */
    private val symbols = mutableListOf<CodeSymbol>()

    /** Diagnostics by exact text; filled by a test before it calls the tool. */
    private val diagnostics = mutableMapOf<String, List<Diagnostic>>()

    /** The Symbols of a file with exactly this text, instead of [symbols]; filled by a test before it calls the tool. */
    private val symbolsByText = mutableMapOf<String, List<CodeSymbol>>()

    /** The Unused Imports by exact text; filled by a test before it calls the tool. */
    private val unusedByText = mutableMapOf<String, Set<String>>()

    private val intelligence = EmptyIntelligence(symbols, diagnostics, symbolsByText, unusedByText)

    private val tools = ToolHarness(root, intelligence)

    /** Calls kt_edit with [arguments], JSON as an agent writes it; whether it failed, and its text. */
    private suspend fun ktEdit(arguments: String): Pair<Boolean, String> = tools.call("kt_edit", arguments)

    @Test
    fun `an Operation's imports are added to its file`() = runTest {
        val (failed, text) = ktEdit(
            """{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= 2", "imports": ["b.Bravo", "import a.x.Alpha"]}]}""",
        )

        assertEquals(false, failed, text)
        assertEquals("package a\n\nimport a.x.Alpha\nimport b.Bravo\n\nfun greet(): Int = 2\n", file.readText())
    }

    // #193: a file that didn't compile before the Edit, importing b.Bar and b.Baz, which nothing refers to.
    private val broken = "package a\n\nimport b.Bar\nimport b.Baz\nimport b.Foo\n\nfun greet(): Foo = missing()\n"
    private val fixed = "package a\n\nimport b.Bar\nimport b.Baz\nimport b.Foo\nimport c.Qux\n\nfun greet(): Foo = Foo(Qux)\n"
    private val fixBroken =
        """{"operations": [{"type": "edit_in", "file": "A.kt", "old": "missing()", "new": "Foo(Qux)", "imports": ["c.Qux"]}]}"""

    @Test
    fun `an Edit fixing a file that didn't compile keeps the imports nothing referred to, when the code intelligence can't tell about its text before`() = runTest {
        file.writeText(broken)
        diagnostics[broken] = listOf(
            Diagnostic(TextRange(Position(6, 19), Position(6, 26)), Severity.ERROR, "Unresolved reference 'missing'.", "UNRESOLVED_REFERENCE"),
        )
        // No answer about the text before the Edit.
        unusedByText[fixed] = setOf("b.Bar", "b.Baz")

        val (failed, text) = ktEdit(fixBroken)

        assertEquals(false, failed, text)
        // Kept for want of an answer, so listed (#209).
        assertEquals("Edited A.kt\nKept imports it couldn't check (no answer from the code intelligence):\n  b.Bar\n  b.Baz", text)
        assertEquals(fixed, file.readText())
    }

    @Test
    fun `an Edit removes and lists the imports the code intelligence shows it made unused`() = runTest {
        file.writeText(broken)
        // b.Bar and b.Baz used before the Edit, by this account.
        unusedByText[broken] = emptySet()
        unusedByText[fixed] = setOf("b.Bar", "b.Baz")

        val (failed, text) = ktEdit(fixBroken)

        assertEquals(false, failed, text)
        assertEquals("Edited A.kt\nRemoved imports it made unused:\n  b.Bar\n  b.Baz", text)
        assertEquals("package a\n\nimport b.Foo\nimport c.Qux\n\nfun greet(): Foo = Foo(Qux)\n", file.readText())
    }

    @Test
    fun `a rejected Edit gives an id to amend, and an amend with only the fix writes the combined Edit`() = runTest {
        val broken = "package a\n\nfun greet(): Int = Bravo.one\n"
        diagnostics[broken] = listOf(
            Diagnostic(TextRange(Position(2, 19), Position(2, 24)), Severity.ERROR, "Unresolved reference 'Bravo'.", "UNRESOLVED_REFERENCE"),
        )

        val (rejected, rejectedText) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= Bravo.one"}]}""")
        val (amended, amendedText) = ktEdit("""{"amend": "e1", "operations": [{"type": "edit_in", "file": "A.kt", "old": "= Bravo", "new": "= Bravo", "imports": ["b.Bravo"]}]}""")
        val (stale, staleText) = ktEdit("""{"amend": "e1", "operations": [{"type": "edit_in", "file": "A.kt", "old": "= Bravo", "new": "= Bravo"}]}""")

        assertEquals(true, rejected)
        assertEquals(
            "Edit rejected (id: e1): 1 new error; nothing written.\n" +
                "  A.kt:L3 in (file level): Unresolved reference 'Bravo'.\n" +
                "      fun greet(): Int = Bravo.one\n" +
                "Fix with kt_edit amend: \"e1\" and only the fixing Operations, or resend the whole batch.",
            rejectedText,
        )
        assertEquals(false, amended, amendedText)
        assertEquals("package a\n\nimport b.Bravo\n\nfun greet(): Int = Bravo.one\n", file.readText())
        assertEquals(true, stale)
        assertEquals("no rejected Edit e1 to amend; resend the whole batch", staleText)
    }

    @Test
    fun `a kt_rename_symbol call drops the rejected Edit, so a later amend of it is refused`() = runTest {
        val broken = "package a\n\nfun greet(): Int = Bravo.one\n"
        diagnostics[broken] = listOf(
            Diagnostic(TextRange(Position(2, 19), Position(2, 24)), Severity.ERROR, "Unresolved reference 'Bravo'.", "UNRESOLVED_REFERENCE"),
        )
        val (rejected, rejectedText) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= Bravo.one"}]}""")
        assertEquals(true, rejected, rejectedText)

        val (renameFailed, _) = tools.call("kt_rename_symbol", """{"symbol": "greet", "newName": "1st", "file": "A.kt"}""")
        val (stale, staleText) = ktEdit("""{"amend": "e1", "operations": [{"type": "edit_in", "file": "A.kt", "old": "= Bravo", "new": "= Bravo", "imports": ["b.Bravo"]}]}""")

        assertEquals(true, renameFailed)
        assertEquals(true, stale)
        assertEquals("no rejected Edit e1 to amend; resend the whole batch", staleText)
        assertEquals("package a\n\nfun greet(): Int = 1\n", file.readText())
    }

    @Test
    fun `an amend that fails through no fault of the rejected Edit says it is still kept, and a corrected amend writes the combined Edit`() = runTest {
        file.writeText("package a\n\nfun greet(): Int {\n    return 1\n}\n")
        val broken = "package a\n\nfun greet(): Int {\n    return Bravo.one\n}\n"
        diagnostics[broken] = listOf(
            Diagnostic(TextRange(Position(3, 11), Position(3, 16)), Severity.ERROR, "Unresolved reference 'Bravo'.", "UNRESOLVED_REFERENCE"),
        )
        val (rejected, rejectedText) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "return 1", "new": "return Bravo.one"}]}""")
        assertEquals(true, rejected, rejectedText)

        val (_, ownText) = ktEdit("""{"amend": "e1", "operations": [{"type": "edit_in", "file": "A.kt", "old": "  return Bravo", "new": "  return Bravo"}]}""")
        val (_, otherIdText) = ktEdit("""{"amend": "e9", "operations": [{"type": "edit_in", "file": "A.kt", "old": "return Bravo", "new": "return Bravo"}]}""")
        intelligence.readiness = Readiness.NotReady("Indexing", 5.seconds)
        val (_, notReadyText) = ktEdit("""{"amend": "e1", "operations": [{"type": "edit_in", "file": "A.kt", "old": "return Bravo", "new": "return Bravo"}]}""")
        intelligence.readiness = Readiness.Ready
        val (amended, amendedText) =
            ktEdit("""{"amend": "e1", "operations": [{"type": "edit_in", "file": "A.kt", "old": "return Bravo", "new": "return Bravo", "imports": ["b.Bravo"]}]}""")

        assertEquals(
            "Operation 0: 'old' starts inside line 4's indentation in A.kt; start it at the beginning of the line or after " +
                "the indentation. Nothing written; the rejected Edit e1 is still kept: amend it again with kt_edit amend: \"e1\" " +
                "and only the fixing Operations.",
            ownText,
        )
        assertEquals(
            "no rejected Edit e9 to amend; the rejected Edit e1 is still kept: amend it with kt_edit amend: \"e1\", or resend the whole batch",
            otherIdText,
        )
        assertEquals(
            "kotlin-lsp still indexing (phase Indexing, 5 s elapsed); nothing returned/written, retry. The rejected Edit e1 is " +
                "still kept: retry with kt_edit amend: \"e1\".",
            notReadyText,
        )
        assertEquals(false, amended, amendedText)
        assertEquals("package a\n\nimport b.Bravo\n\nfun greet(): Int {\n    return Bravo.one\n}\n", file.readText())
    }

    @Test
    fun `the kt_edit description and schema explain amend`() {
        val tool = tools.tool("kt_edit")

        assertTrue("'amend'" in checkNotNull(tool.description), tool.description)
        assertTrue("amend" in tool.inputSchema.properties.toString())
        assertTrue("fails on one of its own Operations, while kotlin-lsp is indexing, or names another id, the rejected Edit is still kept" in checkNotNull(tool.description), tool.description)
    }

    @Test
    fun `the kt_edit description tells how to write a test before the code it calls`() {
        val tool = tools.tool("kt_edit")
        val description = checkNotNull(tool.description)

        assertTrue("TODO()" in description, description)
        assertTrue("same Edit as the test" in description, description)
    }

    @Test
    fun `the kt_edit description says Collateral Errors are reported and never block`() {
        val description = checkNotNull(tools.tool("kt_edit").description)

        assertTrue("Collateral Errors" in description, description)
        assertTrue("never block" in description, description)
    }

    @Test
    fun `the kt_edit description says a KDoc, comment or annotation goes on a Symbol with edit_in, not insert_before`() {
        val description = checkNotNull(tools.tool("kt_edit").description)

        assertTrue("so they don't add a KDoc, comment or annotation to the Symbol itself" in description, description)
        assertTrue("For that, use 'edit_in' on the Symbol" in description, description)
        assertTrue("its first annotation's line if it has one" in description, description)
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

    /** Writes A.kt with CRLF line endings, holding `greet` on lines 3 to 6, and returns its text. */
    private fun crlfGreet(): String {
        val source = "package a\r\n\r\nfun greet(): Int {\r\n    val n = 1\r\n    return n\r\n}\r\n"
        file.writeText(source)
        symbols += CodeSymbol(
            "greet",
            SymbolKind.FUNCTION,
            TextRange(Position(2, 0), Position(5, 1)),
            TextRange(Position(2, 4), Position(2, 9)),
            emptyList(),
            parameterTypes = "",
            type = "(): Int",
        )
        return source
    }

    @Test
    fun `an edit_in whose old has LF line breaks matches in a CRLF file, which keeps CRLF`() = runTest {
        crlfGreet()

        val (failed, _) =
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "symbol": "greet", "old": "val n = 1\n    return n", "new": "val n = 2\n    return n + 1"}]}""")

        assertEquals(false, failed)
        assertEquals("package a\r\n\r\nfun greet(): Int {\r\n    val n = 2\r\n    return n + 1\r\n}\r\n", file.readText())
    }

    @Test
    fun `insert_before, insert_after and delete parse into their Operations and are written`() = runTest {
        val source = file.readText()
        symbolsByText[source] = listOf(
            CodeSymbol("greet", SymbolKind.FUNCTION, TextRange(Position(2, 0), Position(2, 20)), TextRange(Position(2, 4), Position(2, 9)), emptyList(), parameterTypes = "", type = "(): Int"),
        )
        val cases = listOf(
            """{"type": "insert_before", "file": "A.kt", "symbol": "greet", "code": "fun zero(): Int = 0"}""" to
                "package a\n\nfun zero(): Int = 0\n\nfun greet(): Int = 1\n",
            """{"type": "insert_after", "file": "A.kt", "symbol": "greet", "code": "fun two(): Int = 2"}""" to
                "package a\n\nfun greet(): Int = 1\n\nfun two(): Int = 2\n",
            """{"type": "delete", "file": "A.kt", "symbol": "greet"}""" to "package a\n",
        )

        for ((operation, expected) in cases) {
            file.writeText(source)
            val (failed, text) = ktEdit("""{"operations": [$operation]}""")

            assertEquals(false, failed, text)
            assertEquals(expected, file.readText(), operation)
        }
    }

    @Test
    fun `replace, insert_before and insert_after without code fail, naming the type that needs it, writing nothing`() = runTest {
        val before = file.readText()

        for (type in listOf("replace", "insert_before", "insert_after")) {
            val (failed, text) = ktEdit("""{"operations": [{"type": "$type", "file": "A.kt", "symbol": "greet"}]}""")

            assertEquals(true, failed, type)
            assertEquals("Operation 0: missing 'code', which '$type' needs. Nothing written.", text)
        }
        assertEquals(before, file.readText())
    }

    @Test
    fun `the schema offers create, and a create writes the new file, its directories with it, and says it was created`() = runTest {
        val schema = tools.tool("kt_edit").inputSchema.properties.toString()
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
 * Ready unless a test sets [readiness], and knows next to nothing: every file has [symbols] unless [symbolsByText] has its
 * exact text, the project index lists nothing, and there are no references or definitions and no diagnostics but
 * [diagnostics], so every other Edit validates. The Unused Imports of a text are those [unusedByText] gives for it, and
 * the others used; for a text it doesn't give, it has no answer, so every import is undecided.
 */
private class EmptyIntelligence(
    private val symbols: List<CodeSymbol>,
    /** Diagnostics by exact text; none for any other. */
    private val diagnostics: Map<String, List<Diagnostic>>,
    /** The Symbols of a file with exactly this text, instead of [symbols]. */
    private val symbolsByText: Map<String, List<CodeSymbol>>,
    /** The Unused Imports of a file with exactly this text; no answer for any other. */
    private val unusedByText: Map<String, Set<String>> = emptyMap(),
) : UnstubbedCodeIntelligence() {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : UnstubbedAnalysisSession() {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> = symbolsByText[document.text] ?: symbols
        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> = diagnostics[document.text].orEmpty()
        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> = emptyList()
        override suspend fun unusedImports(file: Path, document: TextDocument, among: Set<ImportDirective>, budget: Duration): ImportUsage {
            val unused = unusedByText[document.text] ?: return ImportUsage.undecided(among)
            return ImportUsage(among.filterTo(mutableSetOf()) { it.text in unused })
        }
        override suspend fun writtenBack(file: Path, document: TextDocument) = Unit
    })

    override suspend fun workspaceSymbols(query: String): SymbolSearch = SymbolSearch(emptyList(), SymbolSearch.Completeness.COMPLETE)

    /** What waiting for readiness answers, at once. */
    var readiness: Readiness = Readiness.Ready

    override suspend fun awaitReady(timeout: Duration): Readiness = readiness
}
