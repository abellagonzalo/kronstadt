package kronstadt.server

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.port.AnalysisSession
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** The kt_find_usages tool's arguments as an agent sends them, through to the text it answers. */
class KtFindUsagesToolTest {
    private val root: Path = Files.createTempDirectory("kt-find-usages-tool").toRealPath()

    init {
        root.resolve("A.kt").writeText("package a\n\nfun greet() {}\nfun wave() {}\nfun stay() {}\n")
        root.resolve("B.kt").writeText("package b\n\nimport a.greet\nimport a.wave\n")
        root.resolve("C.kt").writeText("package c\n\nimport a.greet\n")
        root.resolve("Notes.txt").writeText("not Kotlin\n")
    }

    private val tools = ToolHarness(root, ImportsOnlyIntelligence(root))

    private suspend fun findUsages(arguments: String): Pair<Boolean, String> = tools.call("kt_find_usages", arguments)

    @Test
    fun `a Symbol with too many declarations to resolve project-wide fails without declaredIn`() = runTest {
        assertEquals(
            true to "'stay' can't be resolved without a file: so many declarations are named 'stay', dependencies' included, " +
                "that $INDEX_STOPS and may not list them all; pass the file that declares it as declaredIn",
            findUsages("""{"symbol": "stay"}"""),
        )
    }

    @Test
    fun `declaredIn resolves the Symbol in that file`() = runTest {
        assertEquals(
            false to "No usages of stay (searched 3 files)",
            findUsages("""{"symbol": "stay", "declaredIn": "A.kt"}"""),
        )
    }

    @Test
    fun `a missing file passed as declaredIn is named as declaredIn`() = runTest {
        assertEquals(
            true to "declaredIn: File not found: Missing.kt",
            findUsages("""{"symbol": "stay", "declaredIn": "Missing.kt"}"""),
        )
    }

    @Test
    fun `a non-Kotlin file passed as declaredIn is named as declaredIn`() = runTest {
        assertEquals(
            true to "declaredIn: Not a Kotlin file: Notes.txt. kt_find_usages only supports .kt and .kts files.",
            findUsages("""{"symbol": "stay", "declaredIn": "Notes.txt"}"""),
        )
    }

    @Test
    fun `a missing file passed as file is named as file`() = runTest {
        assertEquals(
            true to "file: File not found: Missing.kt",
            findUsages("""{"symbol": "stay", "declaredIn": "A.kt", "file": "Missing.kt"}"""),
        )
    }

    @Test
    fun `a non-Kotlin file passed as file is named as file`() = runTest {
        assertEquals(
            true to "file: Not a Kotlin file: Notes.txt. kt_find_usages only supports .kt and .kts files.",
            findUsages("""{"symbol": "stay", "declaredIn": "A.kt", "file": "Notes.txt"}"""),
        )
    }

    @Test
    fun `a blank declaredIn is as if it were left out`() = runTest {
        val absent = findUsages("""{"symbol": "stay"}""")
        assertEquals(absent, findUsages("""{"symbol": "stay", "declaredIn": ""}"""))
        assertEquals(absent, findUsages("""{"symbol": "stay", "declaredIn": "  "}"""))
    }

    @Test
    fun `a blank file is as if it were left out`() = runTest {
        assertEquals(
            false to "No usages of stay (searched 3 files)",
            findUsages("""{"symbol": "stay", "declaredIn": "A.kt", "file": ""}"""),
        )
        assertEquals(
            false to "No usages of stay (searched 3 files)",
            findUsages("""{"symbol": "stay", "declaredIn": "A.kt", "file": "  "}"""),
        )
    }

    @Test
    fun `no usages in one file names the one file searched`() = runTest {
        assertEquals(
            false to "No usages of stay (searched 1 file)",
            findUsages("""{"symbol": "stay", "declaredIn": "A.kt", "file": "C.kt"}"""),
        )
    }

    @Test
    fun `no usages other than one import says so`() = runTest {
        assertEquals(
            false to "No usages of wave other than 1 import (searched 3 files)",
            findUsages("""{"symbol": "wave", "declaredIn": "A.kt"}"""),
        )
    }

    @Test
    fun `no usages other than several imports counts them`() = runTest {
        assertEquals(
            false to "No usages of greet other than 2 imports (searched 3 files)",
            findUsages("""{"symbol": "greet", "declaredIn": "A.kt"}"""),
        )
    }

    @Test
    fun `no usages other than an import in the one file searched`() = runTest {
        assertEquals(
            false to "No usages of greet other than 1 import (searched 1 file)",
            findUsages("""{"symbol": "greet", "declaredIn": "A.kt", "file": "C.kt"}"""),
        )
    }

    @Test
    fun `a call without a symbol asks for it`() = runTest {
        assertEquals(true to "Missing required argument: symbol", findUsages("""{"declaredIn": "A.kt"}"""))
    }
}

/**
 * Always ready. A.kt declares `greet`, `wave` and `stay` on lines 3 to 5; the index can't list every declaration of any
 * name, so nothing resolves project-wide. `greet` is imported by B.kt and C.kt, `wave` by B.kt alone, `stay` by none.
 */
private class ImportsOnlyIntelligence(private val root: Path) : UnstubbedCodeIntelligence() {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(object : UnstubbedAnalysisSession() {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> =
            if (file.fileName.toString() == "A.kt") listOf(function("greet", 2), function("wave", 3), function("stay", 4)) else emptyList()

        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> =
            when (position.line) {
                2 -> listOf(importOn("B.kt", 2), importOn("C.kt", 2))
                3 -> listOf(importOn("B.kt", 3))
                else -> emptyList()
            }
    })

    override suspend fun workspaceSymbols(query: String): SymbolSearch = SymbolSearch(emptyList(), SymbolSearch.Completeness.INCOMPLETE)

    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready

    /** `fun <name>() {}` on 0-based [line]. */
    private fun function(name: String, line: Int) = CodeSymbol(
        name,
        SymbolKind.FUNCTION,
        TextRange(Position(line, 0), Position(line, 10 + name.length)),
        TextRange(Position(line, 4), Position(line, 4 + name.length)),
        emptyList(),
        parameterTypes = "",
        type = "(): Unit",
    )

    /** The imported name on 0-based [line] of [file]: `import a.<name>`. */
    private fun importOn(file: String, line: Int) = SourceLocation(root.resolve(file), TextRange(Position(line, 9), Position(line, 14)))
}
