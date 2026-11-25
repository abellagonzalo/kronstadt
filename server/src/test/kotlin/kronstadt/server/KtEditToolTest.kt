package kronstadt.server

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
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

    /** What organize-imports answers, by exact text; filled by a test before it calls the tool. */
    private val organized = mutableMapOf<String, String>()

    /** Queries the index answers possibly incompletely; filled by a test before it calls the tool. */
    private val cutOff = mutableSetOf<String>()

    /** The Symbols every file has; filled by a test before it calls the tool. */
    private val symbols = mutableListOf<CodeSymbol>()

    /** Diagnostics by exact text; filled by a test before it calls the tool. */
    private val diagnostics = mutableMapOf<String, List<Diagnostic>>()

    /** The Symbols of a file with exactly this text, instead of [symbols]; filled by a test before it calls the tool. */
    private val symbolsByText = mutableMapOf<String, List<CodeSymbol>>()

    /** What the project index lists; filled by a test before it calls the tool. */
    private val index = mutableListOf<SymbolLocation>()

    /** References to the Symbols of a file, by file name; filled by a test before it calls the tool. */
    private val references = mutableMapOf<String, List<SourceLocation>>()

    /** Diagnostics of a file given every analysed file's text, by file name; set by a test before it calls the tool. */
    private var inContext: (String, Map<String, String>) -> List<Diagnostic>? = { _, _ -> null }

    private val intelligence =
        EmptyIntelligence(organized, cutOff, symbols, diagnostics, symbolsByText, index, references) { file, open -> inContext(file, open) }

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
    fun `a written Edit that broke a caller in another file lists it under Collateral Errors`() = runBlocking {
        // Real time: the check's timeout must not race the caller's read from disk on a virtual clock.
        val caller = root.resolve("B.kt").apply { writeText("package b\n\nimport a.greet\n\nfun use(): Int = greet() + 1\n") }
        val changed = "package a\n\nfun greet(): String = \"1\"\n"
        fun greet(type: String) = CodeSymbol("greet", SymbolKind.FUNCTION, TextRange(Position(2, 0), Position(2, 20)), TextRange(Position(2, 4), Position(2, 9)), parameterTypes = "", type = type)
        symbols += greet("(): Int")
        symbolsByText[caller.readText()] = listOf(
            CodeSymbol("use", SymbolKind.FUNCTION, TextRange(Position(4, 0), Position(4, 28)), TextRange(Position(4, 4), Position(4, 7)), parameterTypes = "", type = "(): Int"),
        )
        symbolsByText[changed] = listOf(greet("(): String"))
        references["A.kt"] = listOf(SourceLocation(caller, TextRange(Position(4, 17), Position(4, 22))))
        val mismatch = Diagnostic(TextRange(Position(4, 17), Position(4, 28)), Severity.ERROR, "Return type mismatch.", "RETURN_TYPE_MISMATCH")
        inContext = { file, open -> if (file == "B.kt" && open["A.kt"] == changed) listOf(mismatch) else null }

        val (failed, text) = ktEdit("""{"operations": [{"type": "replace", "file": "A.kt", "symbol": "greet", "code": "fun greet(): String = \"1\""}]}""")

        assertEquals(false, failed, text)
        assertEquals(
            "Edited A.kt\n" +
                "  greet  // L3-3\n" +
                "Collateral Errors: 1 new error in files this Edit didn't change, which Validation doesn't check. " +
                "Nothing was blocked: the Edit is written; fix them with another Edit.\n" +
                "  B.kt:L5 in use: Return type mismatch.\n" +
                "      fun use(): Int = greet() + 1",
            text,
        )
        assertEquals(changed, file.readText())
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
    fun `the result lists the errors already in the file up to the cap, counts the rest, and says they may be false`() = runTest {
        val source = "package a\n\nfun greet(): Int = 1\n\nfun broken(): Int = x\n"
        val edited = source.replace("= 1", "= 2")
        fun function(name: String, line: Int, length: Int) =
            CodeSymbol(name, SymbolKind.FUNCTION, TextRange(Position(line, 0), Position(line, length)), TextRange(Position(line, 4), Position(line, 4 + name.length)), parameterTypes = "", type = "(): Int")
        symbolsByText[source] = listOf(function("greet", 2, 20), function("broken", 4, 21))
        symbolsByText[edited] = symbolsByText.getValue(source)
        val errors = (1..7).map { Diagnostic(TextRange(Position(4, 20), Position(4, 21)), Severity.ERROR, "Error $it", "ERR") }
        diagnostics[source] = errors
        diagnostics[edited] = errors
        file.writeText(source)

        val (failed, text) = ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "= 1", "new": "= 2"}]}""")

        assertEquals(false, failed, text)
        assertEquals(
            "Edited A.kt\n" +
                "  greet  // L3-3\n" +
                "Errors already in the file:\n" +
                (1..5).joinToString("") { "  L5 in broken: Error $it\n      fun broken(): Int = x\n" } +
                "  (2 more)\n" +
                "They may be false: kotlin-lsp can lag behind edits made outside Kronstadt. Check with the compiler or tests before acting on them.",
            text,
        )
        assertEquals(edited, file.readText())
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
    fun `an edit_in whose old matches only with different indentation fails, saying where and how it differs`() = runTest {
        val source = "package a\n\nfun greet(): Int {\n    val n = 1\n    return n\n}\n"
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

        val (uniform, uniformText) =
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "symbol": "greet", "old": "  val n = 1\n  return n", "new": ""}]}""")
        val (mixed, mixedText) =
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "old": "\tval n = 1\n  return n", "new": ""}]}""")

        assertEquals(true, uniform)
        assertEquals(
            "Operation 0: 'old' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if indentation " +
                "is ignored: the file indents it by 4 spaces, 'old' by 2 spaces. Copy 'old' from kt_read_symbol output. " +
                "Nothing written.",
            uniformText,
        )
        assertEquals(true, mixed)
        assertEquals(
            "Operation 0: 'old' doesn't occur in A.kt exactly as written, but occurs once at line 4 if indentation is " +
                "ignored, where its lines are indented differently from the file's. Read the file to copy it. Nothing written.",
            mixedText,
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `an edit_in whose old has LF line breaks matches in a CRLF file, which keeps CRLF`() = runTest {
        file.writeText("package a\r\n\r\nfun greet(): Int {\r\n    val n = 1\r\n    return n\r\n}\r\n")
        symbols += CodeSymbol(
            "greet",
            SymbolKind.FUNCTION,
            TextRange(Position(2, 0), Position(5, 1)),
            TextRange(Position(2, 4), Position(2, 9)),
            emptyList(),
            parameterTypes = "",
            type = "(): Int",
        )

        val (failed, _) =
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "symbol": "greet", "old": "val n = 1\n    return n", "new": "val n = 2\n    return n + 1"}]}""")

        assertEquals(false, failed)
        assertEquals("package a\r\n\r\nfun greet(): Int {\r\n    val n = 2\r\n    return n + 1\r\n}\r\n", file.readText())
    }

    @Test
    fun `an edit_in whose old doesn't occur in a CRLF file says any line break matches any line ending`() = runTest {
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

        assertEquals(
            true to "Operation 0: 'old' doesn't occur in greet in A.kt, exactly as written (whitespace included; any line break " +
                "matches any line ending). Use kt_read_symbol to see its source. Nothing written.",
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "symbol": "greet", "old": "val n = 2\n    return n", "new": ""}]}"""),
        )
        assertEquals(source, file.readText())
    }

    @Test
    fun `an edit_in missing in a property a bare name chose over a same-named function names its kind and the function`() = runTest {
        val source = "package a\n\nclass Tools {\n    val edit =\n        0\n    fun edit(n: Int): Int {\n        return n\n    }\n}\n"
        file.writeText(source)
        val property = CodeSymbol("edit", SymbolKind.PROPERTY, TextRange(Position(3, 4), Position(4, 9)), TextRange(Position(3, 8), Position(3, 12)), type = ": Int")
        val function = CodeSymbol(
            "edit",
            SymbolKind.FUNCTION,
            TextRange(Position(5, 4), Position(7, 5)),
            TextRange(Position(5, 8), Position(5, 12)),
            parameterTypes = "Int",
            type = "(Int): Int",
        )
        symbols += CodeSymbol("Tools", SymbolKind.CLASS, TextRange(Position(2, 0), Position(8, 1)), TextRange(Position(2, 6), Position(2, 11)), listOf(property, function))

        val (missing, missingText) =
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "symbol": "edit", "old": "return n", "new": "return 0"}]}""")
        val (hinted, hintedText) =
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "symbol": "edit", "old": "val edit =\n  0", "new": ""}]}""")
        val (signed, signedText) =
            ktEdit("""{"operations": [{"type": "edit_in", "file": "A.kt", "symbol": "edit(Int)", "old": "val edit", "new": ""}]}""")

        assertEquals(true, missing)
        assertEquals(
            "Operation 0: 'old' doesn't occur in edit (the property) in A.kt, exactly as written (whitespace included; any line " +
                "break matches any line ending). Use kt_read_symbol to see its source. A function shares its name: edit(Int). " +
                "Nothing written.",
            missingText,
        )
        assertEquals(true, hinted)
        assertEquals(
            "Operation 0: 'old' doesn't occur in edit (the property) in A.kt exactly as written, but occurs once at line 4 if " +
                "indentation is ignored: the file indents line 5 by 8 spaces, 'old' by 2 spaces. Copy 'old' from kt_read_symbol " +
                "output. A function shares its name: edit(Int). Nothing written.",
            hintedText,
        )
        assertEquals(true, signed)
        assertEquals(
            "Operation 0: 'old' doesn't occur in edit(Int) in A.kt, exactly as written (whitespace included; any line break " +
                "matches any line ending). Use kt_read_symbol to see its source. Nothing written.",
            signedText,
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

    @Test
    fun `a Symbol Path without a file that earlier Operations renamed away in each of its files fails, naming each with its close names`() = runTest {
        val source = "package a\n\nfun greet(): Int = 1\n"
        val greets = "package a\n\nfun greets(): Int = 1\n"
        val files = listOf("B.kt", "C.kt").map { root.resolve(it).apply { writeText(source) } }
        val greet = CodeSymbol("greet", SymbolKind.FUNCTION, TextRange(Position(2, 0), Position(2, 20)), TextRange(Position(2, 4), Position(2, 9)), emptyList(), parameterTypes = "", type = "(): Int")
        symbolsByText[source] = listOf(greet)
        symbolsByText[greets] = listOf(
            CodeSymbol("greets", SymbolKind.FUNCTION, TextRange(Position(2, 0), Position(2, 21)), TextRange(Position(2, 4), Position(2, 10)), emptyList(), parameterTypes = "", type = "(): Int"),
        )
        index += files.map { SymbolLocation("greet", SymbolKind.FUNCTION, it, greet.range) }

        val (failed, text) = ktEdit(
            """{"operations": [{"type": "replace", "file": "B.kt", "symbol": "greet", "code": "fun greets(): Int = 1"}, """ +
                """{"type": "replace", "file": "C.kt", "symbol": "greet", "code": "fun greets(): Int = 1"}, """ +
                """{"type": "replace", "symbol": "greet", "code": "fun greet(): Int = 2"}]}""",
        )

        assertEquals(true, failed)
        assertEquals(
            "Operation 2: 'greet' was removed or renamed by earlier Operations of this Edit in B.kt and C.kt; target what " +
                "those Operations left. Close names:\n  greets — B.kt\n  greets — C.kt\n Nothing written.",
            text,
        )
        assertEquals(listOf(source, source), files.map { it.readText() })
    }
}

/**
 * Ready unless a test sets [readiness], and knows next to nothing: every file has [symbols] unless [symbolsByText] has its exact text, the
 * project index lists only [index], and there are no diagnostics, references or definitions, so every Edit validates.
 * Organize-imports answers [organized] for the exact text, else nothing. A search for a query in [cutOff] may miss
 * symbols named exactly like it, as when the index stops at its maximum.
 */
private class EmptyIntelligence(
    private val organized: Map<String, String>,
    private val cutOff: Set<String>,
    private val symbols: List<CodeSymbol>,
    /** Diagnostics by exact text; none for any other. */
    private val diagnostics: Map<String, List<Diagnostic>> = emptyMap(),
    /** The Symbols of a file with exactly this text, instead of [symbols]. */
    private val symbolsByText: Map<String, List<CodeSymbol>> = emptyMap(),
    /** What the project index lists; a search finds those whose name contains its query. */
    private val index: List<SymbolLocation> = emptyList(),
    /** The references to any Symbol declared in a file, by file name; none for any other. */
    private val references: Map<String, List<SourceLocation>> = emptyMap(),
    /** Diagnostics of a file given every analysed file's text by file name, ahead of [diagnostics]; null leaves it to those. */
    private val inContext: (file: String, open: Map<String, String>) -> List<Diagnostic>? = { _, _ -> null },
) : UnstubbedCodeIntelligence() {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : UnstubbedAnalysisSession() {
        /** The text each file was last analysed with, by file name. */
        private val open = mutableMapOf<String, String>()

        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> {
            open[file.name] = document.text
            return symbolsByText[document.text] ?: symbols
        }

        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> {
            open[file.name] = document.text
            return inContext(file.name, open) ?: diagnostics[document.text].orEmpty()
        }

        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> {
            open[file.name] = document.text
            return references[file.name].orEmpty()
        }
        override suspend fun organizeImports(file: Path, document: TextDocument): TextDocument? = organized[document.text]?.let(::TextDocument)
        override suspend fun writtenBack(file: Path, document: TextDocument) = Unit
    })

    override suspend fun workspaceSymbols(query: String): SymbolSearch =
        SymbolSearch(index.filter { it.name.contains(query, ignoreCase = true) }, if (query in cutOff) SymbolSearch.Completeness.INCOMPLETE else SymbolSearch.Completeness.COMPLETE)
    /** What waiting for readiness answers, at once. */
    var readiness: Readiness = Readiness.Ready

    override suspend fun awaitReady(timeout: Duration): Readiness = readiness
}
