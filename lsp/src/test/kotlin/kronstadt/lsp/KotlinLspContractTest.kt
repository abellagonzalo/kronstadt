@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolKind
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
}
