@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.application.Create
import kronstadt.core.application.Delete
import kronstadt.core.application.Edit
import kronstadt.core.application.EditIn
import kronstadt.core.application.EditResult
import kronstadt.core.application.EditedSymbol
import kronstadt.core.application.FileMatches
import kronstadt.core.application.FileRenamed
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindSymbolResult
import kronstadt.core.application.FindUsages
import kronstadt.core.application.FindUsagesResult
import kronstadt.core.application.FoundDefinition
import kronstadt.core.application.FoundSymbol
import kronstadt.core.application.GetDefinition
import kronstadt.core.application.GetDefinitionResult
import kronstadt.core.application.InsertAfter
import kronstadt.core.application.LookupFailure
import kronstadt.core.application.OperationFailure
import kronstadt.core.application.Problem
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadStructureResult
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.ReadSymbolResult
import kronstadt.core.application.RemovedImport
import kronstadt.core.application.RenameResult
import kronstadt.core.application.RenameSymbol
import kronstadt.core.application.RenamedFile
import kronstadt.core.application.Replace
import kronstadt.core.application.ValidationFailure
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.FileChange
import kronstadt.core.domain.ImportUsage
import kronstadt.core.domain.Position
import kronstadt.core.domain.PreferredOverFunctions
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SymbolCandidate
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.TextRange
import kronstadt.core.domain.Validation
import kronstadt.core.domain.imports
import kronstadt.workspace.LocalFileStore
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolKind as LspSymbolKind
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.util.Collections
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Seam 2: pins the kotlin-lsp facts that the fakes and the adapter assume, against the fixture Gradle project in
 * `src/test/fixture`. The fixture is copied to a fresh directory, so every run imports and indexes from scratch.
 *
 * One kotlin-lsp serves the whole class; tests run in order because the first ones observe startup.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class KotlinLspContractTest {
    @JvmField
    @RegisterExtension
    internal val kotlinLsp = KotlinLspHarness()
    private val project get() = kotlinLsp.project
    private val indexDir get() = kotlinLsp.indexDir
    private val lsp get() = kotlinLsp.lsp

    /**
     * Readiness signals from just after [start] subscribes, to pin the order kotlin-lsp sends them in. It may miss the
     * first ones; `import FINISHED` comes seconds later.
     */
    private val signals = Collections.synchronizedList(mutableListOf<ReadinessSignal>())
    private val recording = CoroutineScope(Dispatchers.Default)

    @BeforeAll
    fun start() {
        recording.launch { lsp.readinessSignals.collect { signals += it } }
    }

    @AfterAll
    fun stop() {
        recording.cancel()
    }

    @Test
    @Order(1)
    fun `reports the tested version at initialize`() {
        assertNull(KotlinLspVersion.mismatchWarning(lsp.serverVersion), "serverInfo.version was ${lsp.serverVersion}")
    }

    @Test
    @Order(2)
    fun `is not ready right after initialize, and workspace symbols are silently empty meanwhile`() = runBlocking {
        assertIs<Readiness.NotReady>(lsp.awaitReady(Duration.ZERO))
        assertEquals(emptyList(), lsp.workspaceSymbols("Calculator").symbols)
    }

    @Test
    @Order(3)
    fun `reports importing then indexing, becomes ready once both ended, and then the index answers`() = runBlocking {
        val phases = linkedSetOf<String>()
        withTimeout(5.minutes) {
            while (true) {
                when (val readiness = lsp.awaitReady(Duration.ZERO)) {
                    Readiness.Ready -> break
                    is Readiness.NotReady -> phases += readiness.phase
                }
                delay(20.milliseconds)
            }
        }
        assertEquals(listOf("Importing", "Indexing"), phases.filter { it != "Starting" }, "phases seen: $phases")

        val calculator = lsp.workspaceSymbols("Calculator").symbols.filter { it.name == "Calculator" }
        assertEquals(listOf(SymbolKind.CLASS), calculator.map { it.kind })
        assertEquals(project.resolve("src/main/kotlin/fixture/Shapes.kt"), calculator.single().file)
    }

    @Test
    @Order(4)
    fun `begins another Indexing run a few ms after the import FINISHED, a gap the settle period covers`() {
        val seen = signals.toList()
        val texts = seen.map { it.text }
        val finished = texts.indexOf("import FINISHED")
        assertTrue(finished >= 0, "signals: $texts")
        val next = seen.drop(finished + 1).firstOrNull()
        assertEquals("begin Indexing", next?.text, "signals: $texts")
        val gap = next!!.at - seen[finished].at
        assertTrue(gap < ReadinessTracker.SETTLE, "gap $gap; signals: $texts")
    }

    /**
     * Ready again within a few seconds, not at once (#71). Now and then (2 of ~25 runs on 271.2085.0) kotlin-lsp runs one
     * more short Indexing ~1.1 s after the last one ended, so just after the settle period and later than the run the
     * test above pins, and an instant check can land inside it. Idle for 30 s after it, with `workspace/symbol` queries,
     * kotlin-lsp began no other run.
     */
    @Test
    @Order(4)
    fun `stays ready while idle, give or take a short Indexing run`() = runBlocking {
        assertEquals(Readiness.Ready, lsp.awaitReady(5.seconds))
    }

    @Test
    @Order(5)
    fun `keeps its index in the indexDir it was given`() {
        assertTrue(Files.isDirectory(indexDir), "kotlin-lsp did not create $indexDir")
        assertTrue(Files.list(indexDir).use { it.findAny().isPresent }, "kotlin-lsp left $indexDir empty")
    }

    // documentSymbol facts behind Symbol Paths (ADR 0002) and Symbol Spans (ADR 0004), on fixture/Spans.kt.

    private fun spans(): Pair<TextDocument, List<CodeSymbol>> = runBlocking {
        val file = project.resolve("src/main/kotlin/fixture/Spans.kt")
        val document = TextDocument(Files.readString(file))
        document to lsp.session { it.documentSymbols(file, document) }
    }

    private fun List<CodeSymbol>.find(vararg names: String): CodeSymbol =
        names.drop(1).fold(single { it.name == names.first() }) { parent, name -> parent.children.single { it.name == name } }

    @Test
    @Order(6)
    fun `range starts at the KDoc or comments above and covers annotations and the end-of-line comment`() {
        val (document, symbols) = spans()
        assertEquals(
            "/**\n * KDoc above an annotated class.\n */\n@Suppress(\"unused\")\n@Deprecated(\"probe\")\ninternal class Documented {",
            document.slice(symbols.find("Documented").range).lines().take(6).joinToString("\n"),
        )
        assertEquals(
            "// A line comment directly above.\n    // Two of them.\n    @JvmField\n    val annotated: Int = 1 // trailing comment",
            document.slice(symbols.find("Documented", "annotated").range),
        )
        assertEquals(
            "/* A block comment above. */\n    fun blocky(): Int = 2 /* trailing block */",
            document.slice(symbols.find("Documented", "blocky").range),
        )
    }

    @Test
    @Order(7)
    fun `an enum entry's range includes its separator and its KDoc`() {
        val (document, symbols) = spans()
        assertEquals("/** Entry KDoc. */\n    RED(\"f00\"),", document.slice(symbols.find("Color", "RED").range))
        assertEquals("BLUE(\"00f\");", document.slice(symbols.find("Color", "BLUE").range))
        assertEquals("},", document.slice(symbols.find("Color", "GREEN").range).lines().last().trim())
    }

    @Test
    @Order(7)
    fun `a constructor property's range stops before its trailing comma, and its Symbol Span takes the comma in`() {
        val (document, symbols) = spans()
        val length = symbols.find("Crate", "length")
        val depth = symbols.find("Crate", "depth")
        assertEquals("val length: Int", document.slice(length.range))
        assertEquals("/** Front to back. */\n    val depth: Int", document.slice(depth.range))
        // #68: the `,` (and an end-of-line comment after it) no longer make the last line a shared one.
        assertEquals("    val length: Int,", assertIs<SymbolSpan.Computation.Computed>(SymbolSpan.of(document, length)).span.text)
        assertEquals(
            "    /** Front to back. */\n    val depth: Int, // deep",
            assertIs<SymbolSpan.Computation.Computed>(SymbolSpan.of(document, depth)).span.text,
        )
    }

    @Test
    @Order(8)
    fun `selectionRange is the name, backticks included, except for constructors which get the whole declaration`() {
        val (document, symbols) = spans()
        assertEquals("Documented", document.slice(symbols.find("Documented").selectionRange))
        assertEquals("`backticked name`", document.slice(symbols.find("backticked name").selectionRange))
        val constructor = symbols.find("Color").children.single { it.kind == SymbolKind.CONSTRUCTOR }
        assertEquals("Color", constructor.name)
        assertEquals(constructor.range, constructor.selectionRange)
    }

    @Test
    @Order(9)
    fun `detail gives parameter types without names, then the return type`() {
        val (_, symbols) = spans()
        fun parts(symbol: CodeSymbol) = listOf(symbol.receiver, symbol.parameterTypes, symbol.type)
        assertEquals(listOf(null, "", "(): Int"), parts(symbols.find("Documented", "blocky")))
        assertEquals(
            listOf(null, "List<Map<String, Int>>, vararg String", "(List<Map<String, Int>>, vararg String): Unit"),
            parts(symbols.find("Documented", "generic")),
        )
        assertEquals(listOf(null, "T, (T) -> String", "(T, (T) -> String): String"), parts(symbols.find("Documented", "wrap")))
        assertEquals(listOf(null, null, ": Int"), parts(symbols.find("Documented", "annotated")))
        assertEquals(listOf(null, "String", "(String)"), parts(symbols.find("Color").children.single { it.kind == SymbolKind.CONSTRUCTOR }))
        assertEquals(listOf(null, null, null), parts(symbols.find("Documented")))
    }

    /**
     * kotlin-lsp can't tell `val` from `var`: it reports a constructor property as `Variable` and any other property as
     * `Property` either way, with the same `detail` and no tags, and doesn't report locals at all. So the kind comes from
     * the declaration's own source text.
     */
    @Test
    @Order(9)
    fun `val and var come from the declaration, wherever the property is declared, and locals aren't reported`() = runBlocking<Unit> {
        val path = "src/main/kotlin/fixture/Mutability.kt"
        val file = project.resolve(path)
        val document = TextDocument(Files.readString(file))

        val reported = lsp.reportedDocumentSymbols(file, document)
        fun reported(vararg path: String): DocumentSymbol =
            path.drop(1).fold(reported.single { it.name == path[0] }) { parent, name -> parent.children.single { it.name == name } }
        fun kindDetailTags(vararg path: String) = reported(*path).let { Triple(it.kind, it.detail, it.tags.orEmpty()) }
        assertEquals(Triple(LspSymbolKind.Variable, ": Int", emptyList()), kindDetailTags("Mutability", "ctorVal"))
        assertEquals(Triple(LspSymbolKind.Variable, ": Int", emptyList()), kindDetailTags("Mutability", "ctorVar"))
        assertEquals(Triple(LspSymbolKind.Property, ": Int", emptyList()), kindDetailTags("Mutability", "bodyVal"))
        assertEquals(Triple(LspSymbolKind.Property, ": Int", emptyList()), kindDetailTags("Mutability", "bodyVar"))
        assertEquals(Triple(LspSymbolKind.Property, ": Int", emptyList()), kindDetailTags("topLevelVal"))
        assertEquals(Triple(LspSymbolKind.Property, ": Int", emptyList()), kindDetailTags("topLevelVar"))
        assertEquals(emptyList(), reported("Mutability", "withLocals").children.orEmpty())

        val symbols = lsp.session { it.documentSymbols(file, document) }
        fun kind(vararg path: String) = symbols.find(*path).kind
        assertEquals(SymbolKind.PROPERTY, kind("Mutability", "ctorVal"))
        assertEquals(SymbolKind.VARIABLE, kind("Mutability", "ctorVar"))
        assertEquals(SymbolKind.PROPERTY, kind("Mutability", "bodyVal"))
        assertEquals(SymbolKind.VARIABLE, kind("Mutability", "bodyVar"))
        assertEquals(SymbolKind.PROPERTY, kind("topLevelVal"))
        assertEquals(SymbolKind.VARIABLE, kind("topLevelVar"))

        val structure = assertIs<ReadStructureResult.Structure>(ReadStructure(files, lsp)(path))
        assertEquals(path, structure.file.shown)
        assertEquals(
            listOf(
                "CLASS Mutability L3-15",
                "  CONSTRUCTOR constructor (Int, Int, Int) L4-4",
                "  PROPERTY ctorVal : Int L4-4",
                "  VARIABLE ctorVar : Int L4-4",
                "  PROPERTY bodyVal : Int L5-5",
                "  VARIABLE bodyVar : Int L7-7",
                "  FUNCTION withLocals (): Int L9-14",
                "PROPERTY topLevelVal : Int L17-17",
                "VARIABLE topLevelVar : Int L19-19",
            ),
            structure.rows,
        )
    }

    /**
     * kotlin-lsp reports an `init` block as a constructor named `<class initializer>` with no `detail`, at any depth. A
     * secondary constructor with no parameters is named after its class, with `()` as its `detail`. An `init` block isn't a
     * Symbol, so none reaches the tree, and `<Class>/constructor` isn't made ambiguous by one.
     */
    @Test
    @Order(9)
    fun `init blocks are reported as constructors named class initializer, and dropped from the tree`() = runBlocking<Unit> {
        val path = "src/main/kotlin/fixture/Initializers.kt"
        val file = project.resolve(path)
        val document = TextDocument(Files.readString(file))

        val reported = lsp.reportedDocumentSymbols(file, document)
        fun reported(vararg path: String): DocumentSymbol =
            path.drop(1).fold(reported.single { it.name == path[0] }) { parent, name -> parent.children.single { it.name == name } }
        fun constructors(symbol: DocumentSymbol) =
            symbol.children.orEmpty().filter { it.kind == LspSymbolKind.Constructor }.map { it.name to it.detail }
        val initializer = "<class initializer>" to null
        assertEquals(
            listOf("Initialized" to "(Int)", initializer, "Initialized" to "()", initializer),
            constructors(reported("Initialized")),
        )
        assertEquals(
            "init {\n        total = seed\n    }",
            document.slice(LspMapping.range(reported("Initialized").children.first { it.name == initializer.first }.range)),
        )
        listOf("Nested", "Single", "Companion").forEach { assertEquals(listOf(initializer), constructors(reported("Initialized", it)), it) }
        assertEquals(listOf("Checked" to "(String?, String?)", initializer), constructors(reported("Checked")))

        val structure = assertIs<ReadStructureResult.Structure>(ReadStructure(files, lsp)(path))
        assertEquals(
            listOf(
                "CLASS Initialized L3-35",
                "  CONSTRUCTOR constructor(Int) (Int) L4-4",
                "  PROPERTY seed : Int L4-4",
                "  VARIABLE total : Int L5-5",
                "  CONSTRUCTOR constructor() () L11-11",
                "  CLASS Nested L17-22",
                "  OBJECT Single L24-28",
                "  OBJECT Companion L30-34",
                "CLASS Checked L37-45",
                "  CONSTRUCTOR constructor(String?, String?) (String?, String?) L38-41",
                "  PROPERTY first : String? L39-39",
                "  PROPERTY second : String? L40-40",
            ),
            structure.rows,
        )

        val read = ReadSymbol(files, lsp)
        assertEquals("constructor() in $path, L11-11", assertIs<ReadSymbolResult.Found>(read(path, "Initialized/constructor()")).location)
        // The primary constructor shares its first line with the class head, a span problem of its own (#68).
        assertIs<LookupFailure.SharedLine>(read(path, "Checked/constructor").lookupFailure())
    }

    private val files by lazy { LocalFileStore(project) }

    @Test
    @Order(10)
    fun `kt_read_symbol reads overloads, classes and enum entries from the real tree`() = runBlocking {
        val read = ReadSymbol(files, lsp)
        val shapes = "src/main/kotlin/fixture/Shapes.kt"

        assertEquals(
            ReadSymbolResult.Found("parse(String, Int)", shapes, SymbolSpan(22, 22, "    fun parse(text: String, radix: Int): Int = text.toInt(radix) * scale")),
            read(shapes, "Calculator/parse(String,Int)"),
        )
        val calculator = assertIs<ReadSymbolResult.Found>(read(shapes, "Calculator"))
        assertEquals("Calculator in $shapes, L5-33", calculator.location)
        assertEquals("/** Overloads, a property and a function sharing a name, accessors, constructors and local functions. */", calculator.span.text.lines().first())
        assertEquals(
            listOf("PLUS/apply", "TIMES/apply", "Operation/apply"),
            assertIs<LookupFailure.Ambiguous>(read(shapes, "apply").lookupFailure()).candidates.map { it.path },
        )
        assertEquals("TIMES in $shapes, L57-59", assertIs<ReadSymbolResult.Found>(read(shapes, "TIMES")).location)
    }

    // The special forms live at the end of Spans.kt rather than in a fixture file of their own: with one more file,
    // kotlin-lsp 271.2085.0 often began a second Indexing progress just after reporting ready (see #37).
    @Test
    @Order(11)
    fun `kt_read_symbol resolves the special forms of Symbol Paths against the real tree`() = runBlocking<Unit> {
        val read = ReadSymbol(files, lsp)
        val spansKt = "src/main/kotlin/fixture/Spans.kt"
        val shapes = "src/main/kotlin/fixture/Shapes.kt"
        suspend fun found(file: String, path: String) = assertIs<ReadSymbolResult.Found>(read(file, path), path)
        suspend fun header(file: String, path: String) = found(file, path).location

        // Extension receivers, read from detail (`String.(): String`, `String.: Char`).
        assertEquals("String.shout in $shapes, L64-65", header(shapes, "String.shout"))
        assertEquals("String.decorate in $spansKt, L37-37", header(spansKt, "Formatter/String.decorate"))
        assertEquals("Int.decorate in $spansKt, L39-39", header(spansKt, "Int.decorate"))
        assertEquals("List<T>.second in $spansKt, L91-91", header(spansKt, "List<T>.second"))
        assertEquals("String.firstChar in $spansKt, L93-94", header(spansKt, "String.firstChar"))
        assertEquals(listOf("String.shout"), assertIs<LookupFailure.NotFound>(read(shapes, "shout").lookupFailure()).suggestions.map { it.path })

        // A bare name prefers the property; parentheses select the function.
        val size = found(spansKt, "Sized/size")
        assertEquals("size in $spansKt, L46-46" to PreferredOverFunctions(SymbolKind.PROPERTY, listOf("size(Int)")), size.location to size.preferred)
        assertEquals("size(Int) in $spansKt, L48-48", header(spansKt, "size(Int)"))
        val total = found(shapes, "Calculator/total")
        assertEquals("total in $shapes, L17-17" to PreferredOverFunctions(SymbolKind.PROPERTY, listOf("total()")), total.location to total.preferred)
        assertEquals("total() in $shapes, L19-19", header(shapes, "total()"))

        // Constructors and primary-constructor val parameters.
        assertEquals("Box/constructor(Int) in $spansKt, L55-55", header(spansKt, "Box/constructor(Int)"))
        assertIs<LookupFailure.SharedLine>(read(spansKt, "Box/width").lookupFailure())
        assertIs<LookupFailure.NotFound>(read(spansKt, "Box/height").lookupFailure())

        // Companions, enum entries with bodies, typealiases.
        assertEquals("create in $spansKt, L61-61", header(spansKt, "Widget/Companion/create"))
        assertEquals("create in $shapes, L48-48", header(shapes, "Outer/Factory/create"))
        assertEquals("PALE in $spansKt, L67-67", header(spansKt, "Tint/PALE"))
        assertEquals("hex() in $spansKt, L69-69", header(spansKt, "Tint/DEEP/hex()"))
        assertEquals("Names in $spansKt, L73-73", header(spansKt, "Names"))
        assertEquals("Area in $shapes, L3-3", header(shapes, "Area"))

        // Not addressable: accessors, local declarations, lambdas.
        listOf("counter/get", "Hidden/counter/set", "firstChar/get", "outer/local", "LocalClass", "lambda").forEach { path ->
            assertIs<LookupFailure.NotFound>(read(spansKt, path).lookupFailure(), path)
        }
        assertIs<LookupFailure.NotFound>(read(shapes, "sumOfSquares/square").lookupFailure())
    }

    @Test
    @Order(12)
    fun `kt_read_symbol resolves Symbol Paths without a file, through each candidate's own tree`() = runBlocking<Unit> {
        val read = ReadSymbol(files, lsp)
        val shapes = "src/main/kotlin/fixture/Shapes.kt"
        val spansKt = "src/main/kotlin/fixture/Spans.kt"
        val alpha = "src/main/kotlin/fixture/alpha/Shape.kt"
        val beta = "src/main/kotlin/fixture/beta/Shape.kt"
        suspend fun header(path: String) = assertIs<ReadSymbolResult.Found>(read(null, path), path).location

        assertEquals("parse(String, Int) in $shapes, L23-23", header("Calculator/parse(String,Int)"))
        // Searched by its outermost name, `Outer`, whose files' trees hold the whole path (#215).
        assertEquals("describe in $shapes, L40-40", header("Outer/Nested/describe"))
        // Same-named classes in two packages: ambiguous, until a package prefix narrows them.
        assertEquals(
            listOf(SymbolCandidate("Shape", alpha), SymbolCandidate("Shape", beta)),
            assertIs<LookupFailure.Ambiguous>(read(null, "Shape").lookupFailure()).candidates,
        )
        assertEquals("Shape in $alpha, L3-3", header("fixture.alpha/Shape"))
        assertEquals("Shape in $beta, L3-3", header("fixture.beta/Shape"))
        // The index drops receivers and names constructors after their class; the trees decide.
        assertEquals("String.shout in $shapes, L64-65", header("String.shout"))
        assertEquals("Box/constructor(Int) in $spansKt, L55-55", header("Box/constructor(Int)"))
        assertEquals("create in $spansKt, L61-61", header("Widget/Companion/create"))
        assertEquals("Companion", assertIs<ReadSymbolResult.Found>(read(null, "Widget/Companion")).symbol)
        // The index matches substrings, not typos, so a miss looks up a prefix of the name too.
        assertEquals(emptyList(), lsp.workspaceSymbols("Calculatr").symbols)
        // A miss suggests close names with their files; local declarations stay unaddressable.
        assertEquals(
            listOf(SymbolCandidate("Calculator", shapes)),
            assertIs<LookupFailure.NotFound>(read(null, "Calculatr").lookupFailure()).suggestions,
        )
        assertIs<LookupFailure.NotFound>(read(null, "sumOfSquares/square").lookupFailure())
    }

    @Test
    @Order(13)
    fun `kt_find_symbol prints canonical Symbol Paths of addressable declarations in project source`() = runBlocking<Unit> {
        val find = FindSymbol(files, lsp)
        val shapes = "src/main/kotlin/fixture/Shapes.kt"
        suspend fun found(query: String, kind: SymbolKind? = null) = assertIs<FindSymbolResult.Found>(find(query, kind), query).files

        assertEquals(
            listOf(
                FileMatches(
                    shapes,
                    listOf(
                        FoundSymbol(SymbolKind.FUNCTION, "parse(String)", 21, 21),
                        FoundSymbol(SymbolKind.FUNCTION, "parse(String, Int)", 23, 23),
                    ),
                ),
            ),
            found("parse").map { group -> group.copy(symbols = group.symbols.sortedBy { it.firstLine }) },
        )
        // The index drops receivers; the tree puts them back.
        assertEquals(listOf(FileMatches(shapes, listOf(FoundSymbol(SymbolKind.FUNCTION, "String.shout", 64, 65)))), found("shout"))
        assertEquals(
            setOf(
                FileMatches("src/main/kotlin/fixture/alpha/Shape.kt", listOf(FoundSymbol(SymbolKind.CLASS, "Shape", 3, 3))),
                FileMatches("src/main/kotlin/fixture/beta/Shape.kt", listOf(FoundSymbol(SymbolKind.CLASS, "Shape", 3, 3))),
            ),
            found("Shape", SymbolKind.CLASS).toSet(),
        )
        // The index has no constructors or unnamed companions, so a class name finds the class alone.
        assertEquals(listOf("Calculator"), found("Calculator").flatMap { it.symbols }.map { it.path })
        assertIs<FindSymbolResult.NoMatches>(find("Companion"))
        assertEquals(listOf("Factory"), found("Factory").flatMap { it.symbols }.map { it.path })
        // The index lists a backticked name twice, without backticks; it is found once, quoted as a path.
        assertEquals(listOf("`backticked name`"), found("backticked").flatMap { it.symbols }.map { it.path })
        // The local `square` isn't addressable; the index's substring match finds `sumOfSquares` too.
        assertEquals(listOf("sumOfSquares"), found("square").flatMap { it.symbols }.map { it.path })
    }

    // Validation (ADR 0003): diagnostics are pulled for the exact text the Shadow Workspace sent.

    @Test
    @Order(14)
    fun `pulled diagnostics describe exactly the document text sent, whatever is on disk`() = runBlocking<Unit> {
        val file = project.resolve("src/main/kotlin/fixture/alpha/Shape.kt")
        val disk = TextDocument(Files.readString(file))
        val broken = TextDocument(disk.text.replace("class Shape(val sides: Int)", "class Shape(val sides: Int) {\n    fun name(): Int = \"x\"\n}"))
        lsp.session { session ->
            assertEquals(emptyList(), session.diagnostics(file, disk).filter { it.severity == Severity.ERROR })

            val errors = session.diagnostics(file, broken).filter { it.severity == Severity.ERROR }
            assertEquals(1, errors.size, "diagnostics: $errors")
            assertEquals(Position(3, 22), errors.single().range.start, "diagnostics: $errors")
            assertNotNull(errors.single().code, "diagnostics: $errors")

            assertEquals(emptyList(), session.diagnostics(file, disk).filter { it.severity == Severity.ERROR })
        }
    }

    // kt_edit end to end (ADR 0003): Shadow Workspace, Validation and Write-back against the real server and disk.

    @Test
    @Order(15)
    fun `kt_edit rejects a replace that adds a compiler error, writing nothing`() = runBlocking<Unit> {
        val path = "src/main/kotlin/fixture/alpha/Shape.kt"
        val before = Files.readString(project.resolve(path))

        val result = Edit(files, lsp)(Replace(path, "Shape", "class Shape(val sides: Int) {\n    fun name(): Int = \"x\"\n}"))

        val problem = assertIs<ValidationFailure.Rejected>(assertIs<EditResult.ValidationFailed>(result, "$result").failure).newErrors.single()
        assertEquals(Problem(path, "name", 4, problem.message, "fun name(): Int = \"x\""), problem)
        assertEquals(before, Files.readString(project.resolve(path)))
    }

    // A file's diagnostics depend on the other files' text, so Validation pulls every file's diagnostics before the Edit
    // while all files are still at their text before it (#72).
    @Test
    @Order(16)
    fun `kt_edit rejects a broken call to a signature another file of the same Edit changed, writing neither file`() = runBlocking<Unit> {
        val shapes = "src/main/kotlin/fixture/Shapes.kt"
        val definitions = "src/main/kotlin/fixture/Definitions.kt"
        val shapesBefore = Files.readString(project.resolve(shapes))
        val definitionsBefore = Files.readString(project.resolve(definitions))
        // The edited call has the same error the unedited area(1.0, 2.0) would have against the new signature: pulled after
        // Shapes.kt changed, Definitions.kt's diagnostics before the Edit would already hold it, and the error would pass
        // for one the file had before.
        val uses = $$"""
            |fun uses(radius: Int): String {
            |    val documented = Documented()
            |    val words = listOf("a", "b")
            |    val file = File("x")
            |    return "$radius ${documented.blocky()} ${"hi".shout()} $words ${file.name} ${"7".toInt()} ${area(3.0, 4.0)}"
            |}
        """.trimMargin()
        val call = uses.lines().single { "area(3.0, 4.0)" in it }.trim()

        val result = Edit(files, lsp)(
            listOf(
                Replace(shapes, "/area", "fun area(width: Double, height: Double, depth: Double): Area = width * height * depth"),
                Replace(definitions, "Definitions/uses", uses),
            ),
        )

        assertEquals(
            listOf(Problem(definitions, "uses", 11, "No value passed for parameter 'depth'.", call)),
            assertIs<ValidationFailure.Rejected>(assertIs<EditResult.ValidationFailed>(result, "$result").failure).newErrors,
        )
        assertEquals(shapesBefore, Files.readString(project.resolve(shapes)))
        assertEquals(definitionsBefore, Files.readString(project.resolve(definitions)))
    }

    @Test
    @Order(17)
    fun `kt_edit writes a valid replace back, and kt_find_symbol sees it right away`() = runBlocking<Unit> {
        val path = "src/main/kotlin/fixture/alpha/Shape.kt"

        val result = Edit(files, lsp)(Replace(path, "Shape", "/** Renamed. */\nclass Polygon(val sides: Int)"))

        val written = assertIs<EditResult.Written>(result, "$result").files.single()
        assertEquals(listOf(EditedSymbol("Polygon", 3, 4)), written.symbols)
        assertEquals("package fixture.alpha\n\n/** Renamed. */\nclass Polygon(val sides: Int)\n", Files.readString(project.resolve(path)))
        assertEquals(
            listOf(FileMatches(path, listOf(FoundSymbol(SymbolKind.CLASS, "Polygon", 3, 4)))),
            assertIs<FindSymbolResult.Found>(FindSymbol(files, lsp)("Polygon")).files,
        )
        // Still there for the next search, before kotlin-lsp's file watcher could have caught up.
        assertIs<FindSymbolResult.Found>(FindSymbol(files, lsp)("Polygon"), "second search")
    }

    // A primary constructor has no whole-line span, so kt_edit refuses it and points to a whole-file edit_in instead (#99).
    // Following that advice adds a constructor parameter, and the result lists the class.
    @Test
    @Order(17)
    fun `kt_edit refuses a primary constructor, and a whole-file edit_in adds a parameter to it`() = runBlocking<Unit> {
        val path = "src/main/kotlin/fixture/Initializers.kt"
        val old = "    val second: String?,\n) {"
        val new = "    val second: String?,\n    val third: String? = null,\n) {"

        val refused = assertIs<EditResult.OperationFailed>(Edit(files, lsp)(EditIn(path, "Checked/constructor", old, new)))
        assertIs<LookupFailure.SharedLine>(assertIs<OperationFailure.LookupFailed>(refused.failure).failure)

        val result = Edit(files, lsp)(EditIn(path, null, old, new))

        val written = assertIs<EditResult.Written>(result, "$result").files.single()
        assertEquals(listOf("Checked"), written.symbols.map { it.path })
        assertContains(Files.readString(project.resolve(path)), new)
    }

    // kt_find_usages: textDocument/references without the declaration, attributed through each file's own tree.

    @Test
    @Order(18)
    fun `kt_find_usages lists Usages by containing Symbol, main before test, imports only counted`() = runBlocking<Unit> {
        val find = FindUsages(files, lsp)
        val shapes = "src/main/kotlin/fixture/Shapes.kt"
        val check = "src/test/kotlin/fixture/ShapesCheck.kt"
        val checkLine = 8 to "return Calculator(\"c\").parse(\"2\") == 2 && circle.radius == 1.0 && Operation.PLUS.apply(1, 2) == 3"

        // Main before test; the delegating constructor call `this(name)` refers to the class too.
        assertEquals(
            FindUsagesResult.Found(
                2, 2, 0,
                listOf(
                    inFile(shapes, inSymbol("constructor(String, Int)", 7 to "constructor(name: String, scale: Int) : this(name) {")),
                    inFile(check, inSymbol("checkShapes", checkLine)),
                ),
            ),
            find("Calculator"),
        )
        // Grouped by each overload's canonical path; the declaration itself and the accessors' `field` aren't Usages.
        assertEquals(
            FindUsagesResult.Found(
                4, 1, 0,
                listOf(
                    inFile(
                        shapes,
                        inSymbol("constructor(String, Int)", 8 to "this.scale = scale"),
                        inSymbol("total()", 19 to "fun total(): Int = total * scale"),
                        inSymbol("parse(String)", 21 to "fun parse(text: String): Int = text.toInt() * scale"),
                        inSymbol("parse(String, Int)", 23 to "fun parse(text: String, radix: Int): Int = text.toInt(radix) * scale"),
                    ),
                ),
            ),
            find("Calculator/scale"),
        )
        // Two references on one line: counted twice, shown once.
        assertEquals(FindUsagesResult.Found(2, 1, 0, listOf(inFile(shapes, inSymbol("create", 48 to "fun create(): Outer = Outer()")))), find("Outer"))
        assertEquals(
            FindUsagesResult.Found(1, 1, 1, listOf(inFile(check, inSymbol("checkShapes", 7 to "val circle = Shape(1.0)")))),
            find("fixture.beta/Shape"),
        )
        // The call is a Usage; the overrides in PLUS and TIMES aren't.
        assertEquals(FindUsagesResult.Found(1, 1, 0, listOf(inFile(check, inSymbol("checkShapes", checkLine)))), find("Operation/apply"))
        assertEquals(FindUsagesResult.NoUsages("describe", searchedFiles = 1), find("Outer/Nested/describe", file = "src/main/kotlin/fixture/Shapes.kt"))
    }

    // kt_get_definition: textDocument/definition, on fixture/Definitions.kt.

    @Test
    @Order(19)
    fun `kt_get_definition shows project, local, sources-jar and JDK targets`() = runBlocking<Unit> {
        val get = GetDefinition(files, lsp)
        val definitions = "src/main/kotlin/fixture/Definitions.kt"
        val spansKt = "src/main/kotlin/fixture/Spans.kt"
        suspend fun found(name: String, at: String? = null) =
            assertIs<GetDefinitionResult.Found>(get("Definitions/uses", name, at), name).definitions.single()
        suspend fun inSource(name: String) = assertIs<FoundDefinition.InSource>(found(name), name)
            .let { listOf(it.listing.path.toString(), "${it.file}, L${it.listing.firstLine}-${it.listing.lastLine}", it.head) }

        // Project targets resolve through the target file's tree: definition points at the name.
        assertEquals(
            listOf(
                "Documented",
                "$spansKt, L3-20",
                """
            |/**
            | * KDoc above an annotated class.
            | */
            |@Suppress("unused")
            |@Deprecated("probe")
            |internal class Documented
            """.trimMargin(),
            ),
            inSource("Documented"),
        )
        assertEquals(listOf("blocky", "$spansKt, L14-15", "    /* A block comment above. */\n    fun blocky(): Int"), inSource("blocky"))
        assertEquals(
            listOf("String.shout", "src/main/kotlin/fixture/Shapes.kt, L64-65", "/** Extensions. */\nfun String.shout(): String"),
            inSource("shout"),
        )
        // Locals and parameters: the definition is in the same file, at a name no Symbol has.
        assertEquals(
            FoundDefinition.Local("documented", "uses", definitions, 8, "val documented = Documented()"),
            found("documented", at = "documented.blocky"),
        )
        assertEquals(FoundDefinition.Local("radius", "uses", definitions, 7, "fun uses(radius: Int): String {"), found("radius", at = "\$radius"))
        // Kotlin libraries: a sources-jar entry, with a documentSymbol tree for its jar: URI.
        val listOf = assertIs<FoundDefinition.InLibrary>(found("listOf"))
        assertEquals(
            Triple("kotlin.collections.listOf", "kotlin-stdlib-2.4.21-sources.jar", "commonMain/kotlin/collections/Collections.kt"),
            Triple(listOf.qualifiedName, listOf.origin, listOf.entry),
        )
        assertTrue("Returns a new read-only list of given elements." in listOf.head, listOf.head)
        assertTrue(listOf.head.endsWith("fun <T> listOf(vararg elements: T): List<T>"), listOf.head)
        val toInt = assertIs<FoundDefinition.InLibrary>(found("toInt"))
        assertEquals("kotlin.text.toInt", toInt.qualifiedName)
        assertTrue(toInt.origin.startsWith("kotlin-stdlib-"), toInt.origin)
        assertTrue(toInt.head.endsWith("fun String.toInt(): Int"), toInt.head)
        // The JDK: a class file in a jrt: URI, no sources (e.g. Homebrew's OpenJDK), or a Java file in the JDK's src.zip
        // (e.g. Temurin 25), with the declaration if kotlin-lsp gives Symbols for it and as without sources if not.
        when (val jdk = found("name")) {
            is FoundDefinition.InLibrary -> {
                assertEquals(
                    Triple("java.io.File.getName", "JDK java.base", "java/io/File.java"),
                    Triple(jdk.qualifiedName, jdk.origin, jdk.entry),
                )
                assertTrue("getName()" in jdk.head, jdk.head)
            }
            else -> assertEquals(FoundDefinition.LibraryWithoutSources("name", "java.io.File", "JDK java.base"), jdk)
        }
        // A keyword has no definition.
        assertEquals(GetDefinitionResult.Unresolved("return", "uses", 11, diagnostic = null), get("Definitions/uses", "return"))
    }

    @Test
    @Order(20)
    fun `an unresolved reference has no definition, but a diagnostic on its name`() = runBlocking<Unit> {
        val file = project.resolve("src/main/kotlin/fixture/Definitions.kt")
        val broken = TextDocument(Files.readString(file).replace("area(1.0, 2.0)", "nope(1.0, 2.0)"))
        val name = broken.positionOf(broken.text.indexOf("nope"))
        lsp.session { session ->
            assertEquals(emptyList(), session.definition(file, broken, name))
            val diagnostic = session.diagnostics(file, broken).single { name in it.range }
            assertEquals("UNRESOLVED_REFERENCE", diagnostic.code)
        }
    }

    // kt_edit imports: an Operation's imports reach Validation with its code.

    @Test
    @Order(21)
    fun `kt_edit adds the imports an Operation lists, so code that needs them validates in the same Edit`() = runBlocking<Unit> {
        val path = "src/main/kotlin/fixture/alpha/Shape.kt"
        val before = Files.readString(project.resolve(path))
        val code = "fun timeout(): Duration = Duration.ZERO"

        val without = Edit(files, lsp)(InsertAfter(path, "Polygon", code))
        assertIs<ValidationFailure.Rejected>(assertIs<EditResult.ValidationFailed>(without, "$without").failure)
        assertEquals(before, Files.readString(project.resolve(path)))

        val result = Edit(files, lsp)(InsertAfter(path, "Polygon", code, imports = listOf("kotlin.time.Duration")))

        val written = assertIs<EditResult.Written>(result, "$result").files.single()
        assertEquals(listOf(EditedSymbol("timeout", 8, 8)), written.symbols)
        assertEquals(emptyList(), written.newWarnings)
        assertEquals(
            "package fixture.alpha\n\nimport kotlin.time.Duration\n\n/** Renamed. */\nclass Polygon(val sides: Int)\n\n$code\n",
            Files.readString(project.resolve(path)),
        )
    }

    // kt_edit import removal (ADR 0004): organize-imports is kotlin-lsp's only unused-import signal.

    @Test
    @Order(22)
    fun `organize-imports drops the unused imports of exactly the text sent, sorting the rest its own way`() = runBlocking<Unit> {
        val file = project.resolve("src/main/kotlin/fixture/alpha/Shape.kt")
        val disk = Files.readString(file)
        val header = "package fixture.alpha\n\nimport kotlin.time.Duration\nimport java.nio.file.Path\nimport kotlin.time.*\nimport java.io.File as F\n\n"
        lsp.session { session ->
            // No diagnostic marks an unused import, so Validation never sees one.
            assertEquals(emptyList(), session.diagnostics(file, TextDocument(header + "fun p(): Path? = null\n")))
            assertEquals(
                TextDocument("package fixture.alpha\n\nimport java.nio.file.Path\n\nfun p(): Path? = null\n"),
                lsp.organizedImports(session, file, TextDocument(header + "fun p(): Path? = null\n")),
            )
            assertEquals(
                TextDocument("package fixture.alpha\n\nimport kotlin.time.Duration\nimport java.io.File as F\n\nfun f(): F? = null\nfun d(): Duration = Duration.ZERO\n"),
                lsp.organizedImports(session, file, TextDocument(header + "fun f(): F? = null\nfun d(): Duration = Duration.ZERO\n")),
            )
        }
        // The edit kotlin-lsp asked to apply was refused.
        assertEquals(disk, Files.readString(file))
    }

    @Test
    @Order(23)
    fun `kt_edit removes the imports a delete made unused, and the file still validates`() = runBlocking<Unit> {
        val path = "src/main/kotlin/fixture/alpha/Shape.kt"

        val result = Edit(files, lsp)(Delete(path, "timeout"))

        val written = assertIs<EditResult.Written>(result, "$result").files.single()
        assertEquals(listOf("kotlin.time.Duration"), written.importsRemoved)
        assertEquals("package fixture.alpha\n\n/** Renamed. */\nclass Polygon(val sides: Int)\n", Files.readString(project.resolve(path)))
    }

    // kt_rename_symbol: the shape of kotlin-lsp's rename answer, and the use case end to end.

    @Test
    @Order(24)
    fun `rename edits files never opened, and a class named like its file also renames the file, last`() = runBlocking {
        val shape = project.resolve("src/main/kotlin/fixture/beta/Shape.kt")
        val check = project.resolve("src/test/kotlin/fixture/ShapesCheck.kt")
        val document = TextDocument(Files.readString(shape))
        val name = Position(2, 6)

        lsp.session { session ->
            assertEquals(RenamePreparation.Renameable(TextRange(name, Position(2, 11)), "Shape"), session.prepareRename(shape, document, name))
            assertEquals(
                Rename.Changes(
                    listOf(
                        FileChange.Edited(
                            check,
                            listOf(
                                TextEdit(TextRange(Position(2, 20), Position(2, 25)), "Polygon"),
                                TextEdit(TextRange(Position(6, 17), Position(6, 22)), "Polygon"),
                            ),
                        ),
                        FileChange.Edited(shape, listOf(TextEdit(TextRange(name, Position(2, 11)), "Polygon"))),
                        FileChange.Moved(shape, shape.resolveSibling("Polygon.kt")),
                    ),
                ),
                session.rename(shape, document, name, "Polygon"),
            )
        }
        // Nothing is applied.
        assertEquals(document.text, Files.readString(shape))
    }

    @Test
    @Order(25)
    fun `prepare-rename refuses a keyword, and rename refuses a library declaration prepare-rename let through`() = runBlocking {
        val file = project.resolve("src/main/kotlin/fixture/Shapes.kt")
        val document = TextDocument(Files.readString(file))
        val keyword = Position(5, 0)
        val string = document.positionOf(document.text.indexOf("String): Int = text.toInt() *"))

        lsp.session { session ->
            assertEquals(RenamePreparation.Refused("This element cannot be renamed"), session.prepareRename(file, document, keyword))
            assertIs<RenamePreparation.Renameable>(session.prepareRename(file, document, string))
            assertEquals(Rename.Refused("This element cannot be renamed"), session.rename(file, document, string, "Text"))
        }
    }

    @Test
    @Order(26)
    fun `kt_rename_symbol renames across main and test sources, and moves a class's file`() = runBlocking<Unit> {
        val shapes = "src/main/kotlin/fixture/Shapes.kt"
        val check = "src/test/kotlin/fixture/ShapesCheck.kt"

        // A deliberate error in the test source that neither rename touches, removed again at the end.
        val clean = Files.readString(project.resolve(check))
        val broken = "fun broken(): Int = \"x\""
        Files.writeString(project.resolve(check), "$clean\n$broken\n")
        val checkErrors = listOf(Problem(check, "broken", 11, "Return type mismatch: expected 'Int', actual 'String'.", broken))

        val parse = RenameSymbol(files, lsp)("Calculator/parse(String)", "parseText")

        assertEquals(
            RenameResult.Renamed(
                "parseText",
                listOf(RenamedFile(shapes, usages = 0), RenamedFile(check, usages = 1, preexistingErrors = checkErrors)),
            ),
            parse,
        )
        assertTrue(Files.readString(project.resolve(shapes)).contains("fun parseText(text: String): Int"))
        assertTrue(Files.readString(project.resolve(check)).contains("Calculator(\"c\").parseText(\"2\")"))

        val shape = RenameSymbol(files, lsp)("fixture.beta/Shape", "Polygon")

        val moved = "src/main/kotlin/fixture/beta/Polygon.kt"
        assertEquals(
            RenameResult.Renamed(
                "Polygon",
                listOf(
                    RenamedFile("src/main/kotlin/fixture/beta/Shape.kt", usages = 0),
                    RenamedFile(check, usages = 1, imports = 1, preexistingErrors = checkErrors),
                ),
                fileRenamed = FileRenamed("src/main/kotlin/fixture/beta/Shape.kt", moved),
            ),
            shape,
        )
        assertTrue(Files.notExists(project.resolve("src/main/kotlin/fixture/beta/Shape.kt")))
        assertEquals("package fixture.beta\n\nclass Polygon(val radius: Double)\n", Files.readString(project.resolve(moved)))
        assertTrue(Files.readString(project.resolve(check)).contains("import fixture.beta.Polygon"))

        // No later test sees the deliberate error.
        Files.writeString(project.resolve(check), Files.readString(project.resolve(check)).removeSuffix("\n$broken\n"))
        assertTrue(broken !in Files.readString(project.resolve(check)))
    }

    // kt_edit import removal under a star import (#81): organize-imports collapses a package once 5 of its names are used.

    private val streamsPath = "src/main/kotlin/fixture/gamma/Streams.kt"

    /** 7 java.io names used, `IOException` only by `failure`, and `Serializable` not at all. */
    private val streams = "package fixture.gamma\n\n" +
        "import java.io.Closeable\nimport java.io.File\nimport java.io.IOException\nimport java.io.InputStream\n" +
        "import java.io.OutputStream\nimport java.io.Reader\nimport java.io.Serializable\nimport java.io.Writer\n\n" +
        "fun file(): File? = null\n\nfun input(): InputStream? = null\n\nfun output(): OutputStream? = null\n\n" +
        "fun reader(): Reader? = null\n\nfun writer(): Writer? = null\n\nfun closeable(): Closeable? = null\n\n" +
        "fun failure(): IOException? = null\n"

    @Test
    @Order(27)
    fun `organize-imports answers a star import once 5 names of a package are used, whether the others are used or not`() = runBlocking<Unit> {
        val file = project.resolve(streamsPath)
        val header = "package fixture.gamma\n\n" +
            "import java.io.File\nimport java.io.IOException\nimport java.io.InputStream\nimport java.io.Reader\nimport java.io.Writer\n\n"
        val four = "fun f(): File? = null\nfun i(): InputStream? = null\nfun r(): Reader? = null\nfun w(): Writer? = null\n"
        val five = four + "fun c(): java.io.Closeable? = null\nfun o(): java.io.OutputStream? = null\n"
        val fiveNamed = header.replace("import java.io.File\n", "import java.io.Closeable\nimport java.io.File\n")
            .replace("import java.io.Reader\n", "import java.io.OutputStream\nimport java.io.Reader\n") +
            four + "fun c(): Closeable? = null\nfun o(): OutputStream? = null\n"
        lsp.session { session ->
            // 4 used: the unused IOException is dropped by name.
            assertEquals(
                TextDocument(header.replace("import java.io.IOException\n", "") + four),
                lsp.organizedImports(session, file, TextDocument(header + four)),
            )
            // Fully qualified names don't count towards the star.
            assertEquals(
                TextDocument(header.replace("import java.io.IOException\n", "") + five),
                lsp.organizedImports(session, file, TextDocument(header + five)),
            )
            // 6 imported, 5 used, IOException unused: only the star import is left, so its answer can't tell IOException is unused.
            assertEquals(
                TextDocument("package fixture.gamma\n\nimport java.io.*\n\n" + four + "fun c(): Closeable? = null\nfun o(): OutputStream? = null\n"),
                lsp.organizedImports(session, file, TextDocument(fiveNamed)),
            )
        }
        assertTrue(Files.notExists(file))
    }

    @Test
    @Order(27)
    fun `an explicit import a new star import covers is an Unused Import when removing it adds no error, and used otherwise`() = runBlocking<Unit> {
        val file = project.resolve(streamsPath)
        val document = TextDocument(streams)
        val usingAll = TextDocument("$streams\nfun serializable(): Serializable? = null\n")
        // On disk, as for the Edits below: without the file, the removal check got no diagnostics and settled nothing.
        Files.createDirectories(file.parent)
        Files.writeString(file, streams)
        try {
            lsp.session { session ->
                // Organize-imports answers java.io.* for both, so only removing each import tells which one is unused.
                assertEquals(listOf("java.io.*"), lsp.organizedImports(session, file, document)?.imports?.map { it.text })
                assertEquals(listOf("java.io.*"), lsp.organizedImports(session, file, usingAll)?.imports?.map { it.text })
                assertEquals(
                    setOf("java.io.Serializable"),
                    session.unusedImports(file, document, document.imports.toSet(), Validation.DEFAULT_TIMEOUT).unused.map { it.text }.toSet(),
                )
                assertEquals(ImportUsage(emptySet()), session.unusedImports(file, usingAll, usingAll.imports.toSet(), Validation.DEFAULT_TIMEOUT))
                // Without time for the check, an import a new star covers is undecided, unless the code refers to its name (#209).
                assertEquals(
                    ImportUsage(emptySet(), document.imports.filterTo(mutableSetOf()) { it.text == "java.io.Serializable" }),
                    session.unusedImports(file, document, document.imports.toSet(), Duration.ZERO),
                )
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    @Order(28)
    fun `kt_edit removes an import it made unused under a star import, keeps the used, the already unused and the listed ones`() = runBlocking<Unit> {
        val file = project.resolve(streamsPath)
        Files.createDirectories(file.parent)
        Files.writeString(file, streams)
        try {
            val failure = Edit(files, lsp)(Delete(streamsPath, "failure"))

            val written = assertIs<EditResult.Written>(failure, "$failure").files.single()
            assertEquals(listOf("java.io.IOException"), written.importsRemoved)
            val withoutFailure = streams.replace("import java.io.IOException\n", "").replace("\n\nfun failure(): IOException? = null\n", "\n")
            assertEquals(withoutFailure, Files.readString(file))

            // Still 5 java.io names used after it, so organize-imports answers a star again; the listed Closeable stays.
            val closeable = Edit(files, lsp)(Delete(streamsPath, "closeable", imports = listOf("java.io.Closeable")))

            assertEquals(emptyList(), assertIs<EditResult.Written>(closeable, "$closeable").files.single().importsRemoved)
            assertEquals(withoutFailure.replace("\n\nfun closeable(): Closeable? = null\n", "\n"), Files.readString(file))
            lsp.session { session ->
                val errors = session.diagnostics(file, TextDocument(Files.readString(file))).filter { it.severity == Severity.ERROR }
                assertEquals(emptyList(), errors)
            }
        } finally {
            Files.deleteIfExists(file)
        }
    }

    // kt_rename_symbol's counts and imports (#85): kotlin-lsp answers a rename with minimal character-level diffs, and
    // also deletes imports that were already unused in the files it touches.

    private val symPath = "src/main/kotlin/fixture/gamma/Sym.kt"
    /** Two Usages of `signature`, an import of `Sym` the rename doesn't change, and the already unused `java.io.File`. */
private val symCheckPath = "src/test/kotlin/fixture/SymCheck.kt"
    @Test
    @Order(29)
    fun `kt_rename_symbol counts renamed names, not kotlin-lsp's edits, and keeps an import that was already unused`() = runBlocking<Unit> {
        val symFile = project.resolve(symPath)
        val symCheckFile = project.resolve(symCheckPath)
        val sym = Files.readString(symFile)
        val symCheck = Files.readString(symCheckFile)
        val name = sym.lines().withIndex().first { "val signature" in it.value }.let { (line, text) -> Position(line, text.indexOf("signature")) }
        val answer = lsp.session { session -> session.rename(symFile, TextDocument(sym), name, "parameterTypes") }
        val checkEdits = assertIs<Rename.Changes>(answer, "$answer").changes
            .filterIsInstance<FileChange.Edited>().singleOrNull { it.file == symCheckFile }.let { assertNotNull(it, "$answer") }.edits
        val importLine = symCheck.lines().indexOf("import java.io.File")
        val (removingImport, renaming) = checkEdits.partition { it.range.start.line <= importLine + 1 }
        // signature -> parameterTypes comes as 5 edits per name, two names in the code.
        assertEquals(10, renaming.size, "$checkEdits")
        // The already unused import goes too: emptied by an edit within its line, so counting edits on import lines
        // reported it as an import of the Symbol; its blank line goes in an edit across lines.
        assertEquals(
            listOf(TextRange(Position(importLine, 0), Position(importLine, 19)), TextRange(Position(importLine + 1, 0), Position(importLine + 2, 0))),
            removingImport.map { it.range },
            "$checkEdits",
        )

        val result = RenameSymbol(files, lsp)("Sym/signature", "parameterTypes", file = symPath)

        assertEquals(
            RenameResult.Renamed(
                "parameterTypes",
                listOf(RenamedFile(symPath, usages = 0), RenamedFile(symCheckPath, usages = 2)),
            ),
            result,
        )
        // The import stays where it was; the KDoc's `signature` is no reference, so it stays too.
        assertEquals(
            symCheck.replace("signature = ", "parameterTypes = ").replace("s.signature", "s.parameterTypes"),
            Files.readString(symCheckFile),
        )
        assertEquals(sym.replace("val signature", "val parameterTypes"), Files.readString(symFile))
    }

    // #56: kotlin-lsp analyses other files against a written document Kronstadt keeps open, not against disk.

    @Test
    @Order(30)
    fun `an outside change to a file kt_edit wrote reaches other files' diagnostics at the next session`() = runBlocking<Unit> {
        val answerPath = "src/main/kotlin/fixture/delta/Answer.kt"
        val answerFile = project.resolve(answerPath)
        val useFile = project.resolve("src/main/kotlin/fixture/delta/UseAnswer.kt")
        val use = TextDocument("package fixture.delta\n\nfun useAnswer(): Int = answer()\n")
        suspend fun useErrors() = lsp.session { session -> session.diagnostics(useFile, use) }.filter { it.severity == Severity.ERROR }
        Files.createDirectories(answerFile.parent)
        try {
            Files.writeString(answerFile, "package fixture.delta\n\nfun answer(): Int = 41\n")
            Files.writeString(useFile, use.text)
            // New files kotlin-lsp never had open: its own watcher picks them up.
            withTimeout(1.minutes) {
                while (useErrors().isNotEmpty()) delay(100.milliseconds)
            }

            assertIs<EditResult.Written>(Edit(files, lsp)(Replace(answerPath, "answer", "fun answer(): Int = 42")))
            Files.writeString(answerFile, "package fixture.delta\n\nfun answer(): String = \"42\"\n")

            // No session touches Answer.kt in between: the next one, about UseAnswer.kt only, already sees the new signature.
            val errors = useErrors()
            assertEquals(listOf("RETURN_TYPE_MISMATCH"), errors.map { it.code }, "diagnostics: $errors")
        } finally {
            Files.deleteIfExists(answerFile)
            Files.deleteIfExists(useFile)
        }
    }

    // #73: kotlin-lsp's rename also removes an import the rename made unused. Renaming a member to the name of an imported
    // extension it then shadows leaves the extension's import unused.

    @Test
    @Order(31)
    fun `kotlin-lsp's rename removes an import it made unused, and kt_rename_symbol counts no Usage for it`() = runBlocking<Unit> {
        val thingPath = "src/main/kotlin/fixture/epsilon/Thing.kt"
        val usePath = "src/main/kotlin/fixture/epsilon/use/UseThing.kt"
        val thing = "package fixture.epsilon\n\nclass Thing {\n    fun info(): String = \"member\"\n}\n"
        val describe = "package fixture.epsilon.ext\n\nimport fixture.epsilon.Thing\n\nfun Thing.describe(): String = \"extension\"\n"
        val use = "package fixture.epsilon.use\n\nimport fixture.epsilon.Thing\nimport fixture.epsilon.ext.describe\n\n" +
            "fun use(thing: Thing): String = thing.info() + thing.describe()\n"
        try {
            // Created through kt_edit, so kotlin-lsp knows them right away (#92).
            val created = Edit(files, lsp)(
                listOf(Create(thingPath, thing), Create("src/main/kotlin/fixture/epsilon/ext/Describe.kt", describe), Create(usePath, use)),
            )
            assertIs<EditResult.Written>(created, "$created")
            val thingFile = project.resolve(thingPath)
            val useFile = project.resolve(usePath)

            val answer = lsp.session { session -> session.rename(thingFile, TextDocument(thing), Position(3, 8), "describe") }

            val useEdits = assertIs<Rename.Changes>(answer, "$answer").changes
                .filterIsInstance<FileChange.Edited>().singleOrNull { it.file == useFile }.let { assertNotNull(it, "$answer") }.edits
            val importLine = use.lines().indexOf("import fixture.epsilon.ext.describe")
            val (removingImport, renaming) = useEdits.partition { it.range.start.line <= importLine + 1 }
            // The import is emptied by an edit within its line, and its blank line goes in an edit across lines: the edit
            // spanning lines is no renamed Usage. info -> describe comes as minimal diffs within the line of the call.
            assertEquals(
                listOf(
                    TextEdit(TextRange(Position(importLine, 0), Position(importLine, use.lines()[importLine].length)), ""),
                    TextEdit(TextRange(Position(importLine + 1, 0), Position(importLine + 2, 0)), ""),
                ),
                removingImport,
                "$useEdits",
            )
            assertTrue(renaming.isNotEmpty() && renaming.all { it.range.start.line == importLine + 2 && it.range.end.line == importLine + 2 }, "$useEdits")

            val result = RenameSymbol(files, lsp)("Thing/info", "describe", file = thingPath)

            // One Usage, the call; neither the import kotlin-lsp removed nor its blank line counts. Kronstadt puts the import
            // back, then removes it as made unused, since organize-imports sees Thing.kt renamed too (#138).
            assertEquals(
                RenameResult.Renamed(
                    "describe",
                    listOf(RenamedFile(thingPath, usages = 0), RenamedFile(usePath, usages = 1)),
                    importsRemoved = listOf(RemovedImport(usePath, "fixture.epsilon.ext.describe")),
                ),
                result,
            )
            assertEquals(
                "package fixture.epsilon.use\n\nimport fixture.epsilon.Thing\n\n" +
                    "fun use(thing: Thing): String = thing.describe() + thing.describe()\n",
                Files.readString(useFile),
            )
        } finally {
            project.resolve("src/main/kotlin/fixture/epsilon").deleteRecursively()
        }
    }

    // #154: a replace that leaves fewer calls to an imported extension, one of them on the result of a helper it moved out
    // to a file the same Edit creates. The import still has a call, so it isn't made unused.

    @Test
    @Order(32)
    fun `kt_edit keeps an import its replace still uses, while another Operation creates the file of a helper it now calls`() = runBlocking<Unit> {
        val fooPath = "src/main/kotlin/fixture/zeta/Foo.kt"
        val barPath = "src/main/kotlin/fixture/zeta/Bar.kt"
        val foo = "package fixture.zeta\n\nimport java.nio.file.Path\nimport kotlin.io.path.readText\n\n" +
            "class Foo {\n" +
            "    fun doSomething(a: Path, b: Path): String = a.readText() + logOf(b).readText()\n\n" +
            "    private fun logOf(dir: Path): Path = dir.resolveSibling(\"x.log\")\n" +
            "}\n"
        val newFoo = "class Foo {\n    fun doSomething(a: Path): String = logOf(a).readText()\n}"
        val bar = "package fixture.zeta\n\nimport java.nio.file.Path\n\nfun logOf(dir: Path): Path = dir.resolveSibling(\"x.log\")\n"
        try {
            // Created through kt_edit, so kotlin-lsp knows it right away (#92).
            val created = Edit(files, lsp)(Create(fooPath, foo))
            assertIs<EditResult.Written>(created, "$created")

            val result = Edit(files, lsp)(listOf(Replace(fooPath, "Foo", newFoo), Create(barPath, bar)))

            val written = assertIs<EditResult.Written>(result, "$result").files
            assertEquals(listOf(fooPath, barPath), written.map { it.file })
            assertEquals(emptyList(), written.flatMap { it.importsRemoved })
            assertEquals(
                "package fixture.zeta\n\nimport java.nio.file.Path\nimport kotlin.io.path.readText\n\n$newFoo\n",
                Files.readString(project.resolve(fooPath)),
            )
            assertEquals(bar, Files.readString(project.resolve(barPath)))
        } finally {
            project.resolve("src/main/kotlin/fixture/zeta").deleteRecursively()
        }
    }

    // #193: kotlin-lsp 271.2085.0's organize-imports on a text with compile errors. It still drops exactly the imports nothing
    // refers to, so it doesn't explain an Edit removing imports that were already unused. Getting no answer about the text
    // before the Edit would: that used to count as "none of them was unused".

    private val brokenPath = "src/main/kotlin/fixture/eta/Broken.kt"
    /** Imports `Path`, used, and `Closeable` and `File`, which nothing refers to. */
    private val brokenHeader = "package fixture.eta\n\nimport java.io.Closeable\nimport java.io.File\nimport java.nio.file.Path\n\n"

    @Test
    @Order(33)
    fun `organize-imports drops the imports nothing refers to from a text that doesn't compile too`() = runBlocking<Unit> {
        val file = project.resolve(brokenPath)
        val bodies = listOf(
            "fun broken(): Path = missing()\n",
            "fun broken(): Missing = TODO()\nfun p(): Path? = null\n",
            "fun broken(p: Path): Int = p.missingMember\n",
            "class Foo {\n    fun doSomething(p: Path): String {\n        val x = missing(p)\n        return x.toString()\n    }\n}\n",
            "fun broken(p: Path): Int = (1 +\n",
            "fun broken(p: Path): Int = p\n",
        )
        Files.createDirectories(file.parent)
        Files.writeString(file, brokenHeader + bodies.first())
        try {
            lsp.session { session ->
                for (body in bodies) {
                    val document = TextDocument(brokenHeader + body)
                    assertTrue(session.diagnostics(file, document).any { it.severity == Severity.ERROR }, body)
                    assertEquals(listOf("java.nio.file.Path"), lsp.organizedImports(session, file, document)?.imports?.map { it.text }, body)
                    val usage = session.unusedImports(file, document, document.imports.toSet(), Validation.DEFAULT_TIMEOUT)
                    assertEquals(setOf("java.io.Closeable", "java.io.File") to emptySet(), usage.unused.map { it.text }.toSet() to usage.undecided, body)
                }
            }
        } finally {
            project.resolve("src/main/kotlin/fixture/eta").deleteRecursively()
        }
    }

    @Test
    @Order(34)
    fun `kt_edit fixing a file that didn't compile keeps the imports nothing referred to, and removes none`() = runBlocking<Unit> {
        val file = project.resolve(brokenPath)
        val broken = brokenHeader + "class Foo {\n    fun doSomething(p: Path): String {\n        val x = missing(p)\n        return x.toString()\n    }\n}\n"
        Files.createDirectories(file.parent)
        Files.writeString(file, broken)
        try {
            val result = Edit(files, lsp)(
                listOf(
                    EditIn(brokenPath, "Foo/doSomething", "missing(p)", "p.fileName"),
                    InsertAfter(brokenPath, "Foo", "fun d(): Duration = Duration.ZERO", imports = listOf("kotlin.time.Duration")),
                ),
            )

            assertEquals(emptyList(), assertIs<EditResult.Written>(result, "$result").files.single().importsRemoved)
            assertEquals(
                broken.replace("import java.nio.file.Path\n", "import java.nio.file.Path\nimport kotlin.time.Duration\n")
                    .replace("missing(p)", "p.fileName") + "\nfun d(): Duration = Duration.ZERO\n",
                Files.readString(file),
            )
        } finally {
            project.resolve("src/main/kotlin/fixture/eta").deleteRecursively()
        }
    }
}

/** The [LookupFailure] a read reports; fails unless it reports one. */
private fun ReadSymbolResult.lookupFailure(): LookupFailure = assertIs<ReadSymbolResult.LookupFailed>(this).failure
