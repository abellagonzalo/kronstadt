@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolSearch
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
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Seam 2 (#38): does kotlin-lsp cap `workspace/symbol` results? File-less Symbol Path resolution trusts that every
 * file declaring a name comes back; a cap would let it resolve a common name to the one file that did (ADR 0002).
 * Declares one name in [FILES] generated files, well past the usual IntelliJ limits (100, 500, 1000), so the shared
 * fixture stays small. Uses its own copy of the fixture, and so its own kotlin-lsp.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WorkspaceSymbolCapContractTest {
    private lateinit var project: Path
    private lateinit var indexDir: Path
    private lateinit var lsp: LspCodeIntelligence

    @BeforeAll
    fun start() {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :lsp:contractTest" })
        project = Files.createTempDirectory("kronstadt-fixture-many").toRealPath()
        fixture.copyToRecursively(project, followLinks = false, overwrite = true)
        val many = project.resolve("src/main/kotlin/fixture/many").createDirectories()
        for (i in 1..FILES) many.resolve("Many$i.kt").writeText("package fixture.many\n\nfun $NAME$i(): Int = $i\n\nobject Holder$i {\n    fun $NAME(): Int = $i\n}\n")
        many.resolve("Getter.kt").writeText("package fixture.many\n\nclass Getter {\n    operator fun get(index: Int): Int = index\n}\n")
        val command = (System.getenv("KRONSTADT_LANGUAGE_SERVER") ?: "kotlin-lsp --stdio").trim().split(Regex("\\s+"))
        indexDir = Files.createTempDirectory("kronstadt-index").toRealPath().resolve("kotlin-server")
        lsp = runBlocking { LspCodeIntelligence.start(command, project, indexDir) }
    }

    @AfterAll
    fun stop() {
        if (::lsp.isInitialized) lsp.close()
        if (::project.isInitialized) project.deleteRecursively()
        if (::indexDir.isInitialized) indexDir.parent.deleteRecursively()
    }

    @Test
    @Order(1)
    fun `becomes ready`() = runBlocking {
        withTimeout(10.minutes) {
            while (lsp.awaitReady(Duration.ZERO) != Readiness.Ready) delay(20.milliseconds)
        }
    }

    // kotlin-lsp returns at most 200 results, with no sign that it left any out: here 200 of the 1500 exact matches.
    @Test
    @Order(2)
    fun `workspace symbols stop at 200 results, silently, and a page full of one name may miss some of it`() = runBlocking {
        val search = lsp.workspaceSymbols(NAME)
        val files = search.symbols.filter { it.name == NAME }.map { it.file }.toSet()

        assertEquals(LspMapping.WORKSPACE_SYMBOL_CAP, search.symbols.size)
        assertTrue(files.size < FILES, "${files.size} of $FILES files came back")
        assertEquals(SymbolSearch.Completeness.INCOMPLETE, search.completeness)
        assertEquals(SymbolSearch.Completeness.COMPLETE, lsp.workspaceSymbols("${NAME}15").completeness)
    }

    // The adapter relies on this order: a full page that ends in other names has listed every exact match.
    @Test
    @Order(3)
    fun `names equal to the query come first, so a page cut off among longer names lists every exact match`() = runBlocking {
        val search = lsp.workspaceSymbols("Holder1")
        val names = search.symbols.map { it.name }

        assertEquals(LspMapping.WORKSPACE_SYMBOL_CAP, names.size)
        assertEquals(listOf("Holder1"), names.takeWhile { it == "Holder1" }.distinct())
        assertTrue("Holder1" !in names.dropWhile { it == "Holder1" })
        assertEquals(SymbolSearch.Completeness.EXACT_NAMES_COMPLETE, search.completeness)
    }

    // Dependency symbols count toward the 200 too: a short name like `get` fills the page with the standard library's.
    @Test
    @Order(4)
    fun `a common short name fills the page with dependency symbols, yet the project's own declaration comes back`() = runBlocking {
        val search = lsp.workspaceSymbols("get")
    
        assertEquals(listOf("Getter.kt"), search.symbols.filter { it.name == "get" }.map { it.file.fileName.toString() })
        assertEquals(SymbolSearch.Completeness.EXACT_NAMES_COMPLETE, search.completeness)
    }

    private companion object {
        const val FILES = 1500
        const val NAME = "commonName"
    }
}
