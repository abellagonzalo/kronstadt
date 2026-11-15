@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.ReadSymbolResult
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.FileStore
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

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
    private lateinit var project: Path
    private lateinit var lsp: LspCodeIntelligence

    @BeforeAll
    fun start() {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :lsp:contractTest" })
        project = Files.createTempDirectory("kronstadt-fixture").toRealPath()
        fixture.copyToRecursively(project, followLinks = false, overwrite = true)
        val command = (System.getenv("KRONSTADT_LANGUAGE_SERVER") ?: "kotlin-lsp --stdio").trim().split(Regex("\\s+"))
        lsp = runBlocking { LspCodeIntelligence.start(command, project) }
    }

    @AfterAll
    fun stop() {
        if (::lsp.isInitialized) lsp.close()
        if (::project.isInitialized) project.deleteRecursively()
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
        assertEquals(emptyList(), lsp.workspaceSymbols("Calculator"))
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

        val calculator = lsp.workspaceSymbols("Calculator").filter { it.name == "Calculator" }
        assertEquals(listOf(SymbolKind.CLASS), calculator.map { it.kind })
        assertEquals(project.resolve("src/main/kotlin/fixture/Shapes.kt"), calculator.single().file)
    }

    @Test
    @Order(4)
    fun `stays ready while idle`() = runBlocking {
        assertEquals(Readiness.Ready, lsp.awaitReady(Duration.ZERO))
    }

    // documentSymbol facts behind Symbol Paths (ADR 0002) and Symbol Spans (ADR 0004), on fixture/Spans.kt.

    private fun spans(): Pair<TextDocument, List<CodeSymbol>> = runBlocking {
        val file = project.resolve("src/main/kotlin/fixture/Spans.kt")
        val document = TextDocument(Files.readString(file))
        try {
            document to lsp.documentSymbols(file, document)
        } finally {
            lsp.closeDocument(file)
        }
    }

    private fun List<CodeSymbol>.find(vararg names: String): CodeSymbol =
        names.drop(1).fold(single { it.name == names.first() }) { parent, name -> parent.children.single { it.name == name } }

    @Test
    @Order(5)
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
    @Order(6)
    fun `an enum entry's range includes its separator and its KDoc`() {
        val (document, symbols) = spans()
        assertEquals("/** Entry KDoc. */\n    RED(\"f00\"),", document.slice(symbols.find("Color", "RED").range))
        assertEquals("BLUE(\"00f\");", document.slice(symbols.find("Color", "BLUE").range))
        assertEquals("},", document.slice(symbols.find("Color", "GREEN").range).lines().last().trim())
    }

    @Test
    @Order(7)
    fun `selectionRange is the name, backticks included, except for constructors which get the whole declaration`() {
        val (document, symbols) = spans()
        assertEquals("Documented", document.slice(symbols.find("Documented").selectionRange))
        assertEquals("`backticked name`", document.slice(symbols.find("backticked name").selectionRange))
        val constructor = symbols.find("Color").children.single { it.kind == SymbolKind.CONSTRUCTOR }
        assertEquals("Color", constructor.name)
        assertEquals(constructor.range, constructor.selectionRange)
    }

    @Test
    @Order(8)
    fun `detail renders parameter types without names, then the return type`() {
        val (_, symbols) = spans()
        assertEquals("(): Int", symbols.find("Documented", "blocky").detail)
        assertEquals("(List<Map<String, Int>>, vararg String): Unit", symbols.find("Documented", "generic").detail)
        assertEquals("(T, (T) -> String): String", symbols.find("Documented", "wrap").detail)
        assertEquals(": Int", symbols.find("Documented", "annotated").detail)
        assertEquals("(String)", symbols.find("Color").children.single { it.kind == SymbolKind.CONSTRUCTOR }.detail)
        assertNull(symbols.find("Documented").detail)
    }

    private val files = object : FileStore {
        override fun resolve(path: String): Path = project.resolve(path)
        override suspend fun read(file: Path): TextDocument = TextDocument(Files.readString(file))
        override fun display(file: Path): String = project.relativize(file).toString()
    }

    @Test
    @Order(9)
    fun `kt_read_symbol reads overloads, classes and enum entries from the real tree`() = runBlocking {
        val read = ReadSymbol(files, lsp)
        val shapes = "src/main/kotlin/fixture/Shapes.kt"

        assertEquals(
            "// parse(String, Int) in $shapes, L23-23\n    fun parse(text: String, radix: Int): Int = text.toInt(radix) * scale",
            assertIs<ReadSymbolResult.Found>(read(shapes, "Calculator/parse(String,Int)")).text,
        )
        val calculator = assertIs<ReadSymbolResult.Found>(read(shapes, "Calculator")).text.lines()
        assertEquals("// Calculator in $shapes, L5-33", calculator.first())
        assertEquals("/** Overloads, a property and a function sharing a name, accessors, constructors and local functions. */", calculator[1])
        assertEquals(
            listOf("PLUS/apply", "TIMES/apply", "Operation/apply"),
            assertIs<ReadSymbolResult.Ambiguous>(read(shapes, "apply")).candidates,
        )
        assertEquals("// TIMES in $shapes, L57-59", assertIs<ReadSymbolResult.Found>(read(shapes, "TIMES")).text.lines().first())
    }

    // The special forms live at the end of Spans.kt rather than in a fixture file of their own: with one more file,
    // kotlin-lsp 271.2085.0 often began a second Indexing progress just after reporting ready (see #37).
    @Test
    @Order(10)
    fun `kt_read_symbol resolves the special forms of Symbol Paths against the real tree`() = runBlocking<Unit> {
        val read = ReadSymbol(files, lsp)
        val spansKt = "src/main/kotlin/fixture/Spans.kt"
        val shapes = "src/main/kotlin/fixture/Shapes.kt"
        suspend fun header(file: String, path: String) = assertIs<ReadSymbolResult.Found>(read(file, path), path).text.lines().first()

        // Extension receivers, read from detail (`String.(): String`, `String.: Char`).
        assertEquals("// String.shout in $shapes, L64-65", header(shapes, "String.shout"))
        assertEquals("// String.decorate in $spansKt, L37-37", header(spansKt, "Formatter/String.decorate"))
        assertEquals("// Int.decorate in $spansKt, L39-39", header(spansKt, "Int.decorate"))
        assertEquals("// List<T>.second in $spansKt, L91-91", header(spansKt, "List<T>.second"))
        assertEquals("// String.firstChar in $spansKt, L93-94", header(spansKt, "String.firstChar"))
        assertEquals(listOf("String.shout"), assertIs<ReadSymbolResult.NotFound>(read(shapes, "shout")).suggestions)

        // A bare name prefers the property; parentheses select the function.
        assertEquals("// size in $spansKt, L46-46", header(spansKt, "Sized/size"))
        assertEquals("// size(Int) in $spansKt, L48-48", header(spansKt, "size(Int)"))
        assertEquals("// total in $shapes, L17-17", header(shapes, "Calculator/total"))
        assertEquals("// total() in $shapes, L19-19", header(shapes, "total()"))

        // Constructors and primary-constructor val parameters.
        assertEquals("// Box/constructor(Int) in $spansKt, L55-55", header(spansKt, "Box/constructor(Int)"))
        assertIs<ReadSymbolResult.SharedLine>(read(spansKt, "Box/width"))
        assertIs<ReadSymbolResult.NotFound>(read(spansKt, "Box/height"))

        // Companions, enum entries with bodies, typealiases.
        assertEquals("// create in $spansKt, L61-61", header(spansKt, "Widget/Companion/create"))
        assertEquals("// create in $shapes, L48-48", header(shapes, "Outer/Factory/create"))
        assertEquals("// PALE in $spansKt, L67-67", header(spansKt, "Tint/PALE"))
        assertEquals("// hex() in $spansKt, L69-69", header(spansKt, "Tint/DEEP/hex()"))
        assertEquals("// Names in $spansKt, L73-73", header(spansKt, "Names"))
        assertEquals("// Area in $shapes, L3-3", header(shapes, "Area"))

        // Not addressable: accessors, local declarations, lambdas.
        listOf("counter/get", "Hidden/counter/set", "firstChar/get", "outer/local", "LocalClass", "lambda").forEach { path ->
            assertIs<ReadSymbolResult.NotFound>(read(spansKt, path), path)
        }
        assertIs<ReadSymbolResult.NotFound>(read(shapes, "sumOfSquares/square"))
    }
}
