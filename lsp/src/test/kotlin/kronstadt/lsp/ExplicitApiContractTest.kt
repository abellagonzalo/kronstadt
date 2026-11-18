@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.application.Edit
import kronstadt.core.application.EditResult
import kronstadt.core.application.EditedSymbol
import kronstadt.core.application.InsertAfter
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Severity
import kronstadt.core.domain.TextDocument
import kronstadt.workspace.LocalFileStore
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
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Seam 2, for a build in explicit-API mode (#48): Gradle applies it to main sources only, but kotlin-lsp reports its
 * diagnostics in test sources too. Pins that upstream behaviour, which Validation works around, and kt_edit on both
 * kinds of source. Uses its own fixture, `src/test/fixture-explicit-api`, and so its own kotlin-lsp.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class ExplicitApiContractTest {
    private lateinit var project: Path
    private lateinit var indexDir: Path
    private lateinit var lsp: LspCodeIntelligence
    private val files by lazy { LocalFileStore(project) }

    @BeforeAll
    fun start() {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.explicitApiFixture")) { "run via ./gradlew :lsp:contractTest" })
        project = Files.createTempDirectory("kronstadt-fixture-explicit-api").toRealPath()
        fixture.copyToRecursively(project, followLinks = false, overwrite = true)
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
        withTimeout(5.minutes) {
            while (lsp.awaitReady(Duration.ZERO) != Readiness.Ready) delay(20.milliseconds)
        }
    }

    @Test
    @Order(2)
    fun `kotlin-lsp reports explicit-API errors in test sources, which the build compiles without them`() = runBlocking {
        val file = project.resolve(TEST_FILE)
        val errors = lsp.session { session -> session.diagnostics(file, TextDocument(Files.readString(file))) }
            .filter { it.severity == Severity.ERROR }

        assertEquals(
            listOf("NO_EXPLICIT_VISIBILITY_IN_API_MODE", "NO_EXPLICIT_RETURN_TYPE_IN_API_MODE"),
            errors.map { it.code },
            "diagnostics: $errors",
        )
    }

    @Test
    @Order(3)
    fun `kt_edit adds a declaration to a test source, which then reports no errors already there`() = runBlocking {
        val result = Edit(files, lsp)(InsertAfter(TEST_FILE, "answerIsRight", "fun answerIsStillRight() = answer() == 42"))

        val written = assertIs<EditResult.Written>(result, "$result").files.single()
        assertEquals(listOf(EditedSymbol("answerIsStillRight", 6, 6)), written.symbols)
        assertEquals(0, written.preexistingErrors)
    }

    @Test
    @Order(4)
    fun `kt_edit still rejects a main declaration that explicit-API mode refuses`() = runBlocking {
        val result = Edit(files, lsp)(InsertAfter(MAIN_FILE, "answer", "fun question() = 6 * 7"))

        val errors = assertIs<EditResult.Rejected>(result, "$result").newErrors
        assertEquals(listOf(6, 6), errors.map { it.line }, "$errors")
    }

    private companion object {
        const val MAIN_FILE = "src/main/kotlin/api/Answer.kt"
        const val TEST_FILE = "src/test/kotlin/api/AnswerCheck.kt"
    }
}
