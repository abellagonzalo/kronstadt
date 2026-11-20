@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Severity
import kronstadt.core.domain.TextDocument
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
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Seam 2, the spike for kt_edit's `create` (#70): can kotlin-lsp analyse a document that `didOpen` gives it but that
 * isn't on disk (yet), in a main source set and in a test one?
 *
 * Outcome (kotlin-lsp 271.2085.0): yes, in both. Such a document gets its `documentSymbols`, its own diagnostics (a
 * deliberate type error is reported), and while it is open another open document that uses its class resolves it,
 * adding no errors; without it open, the same use is an unresolved reference. Nothing is written to disk. So the
 * Shadow Workspace can hold a created file like any other, without a placeholder on disk.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class UnsavedFileContractTest {
    private lateinit var project: Path
    private lateinit var indexDir: Path
    private lateinit var lsp: LspCodeIntelligence

    @BeforeAll
    fun start() {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :lsp:contractTest" })
        project = Files.createTempDirectory("kronstadt-fixture-unsaved").toRealPath()
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
    fun `kotlin-lsp lists the declarations of a file that isn't on disk, in main`() = listsDeclarations("main")

    @Test
    @Order(3)
    fun `kotlin-lsp lists the declarations of a file that isn't on disk, in test`() = listsDeclarations("test")

    private fun listsDeclarations(sourceSet: String) = runBlocking {
        val file = unsaved(sourceSet)

        val symbols = lsp.session { session -> session.documentSymbols(file, TextDocument(GREETER)) }

        assertEquals(listOf("Greeter"), symbols.map { it.name })
        assertEquals(listOf("greet"), symbols.single().children.map { it.name })
        assertFalse(file.exists())
    }

    @Test
    @Order(4)
    fun `kotlin-lsp reports a type error in a file that isn't on disk, in main`() = reportsTypeError("main")

    @Test
    @Order(5)
    fun `kotlin-lsp reports a type error in a file that isn't on disk, in test`() = reportsTypeError("test")

    private fun reportsTypeError(sourceSet: String) = runBlocking {
        val file = unsaved(sourceSet)
        val broken = GREETER.replace("= \"hello\"", "= 42")

        val errors = lsp.session { session -> session.diagnostics(file, TextDocument(broken)) }
            .filter { it.severity == Severity.ERROR }

        assertEquals(listOf("RETURN_TYPE_MISMATCH"), errors.map { it.code }, "diagnostics: $errors")
        assertEquals(listOf(3), errors.map { it.range.start.line }, "diagnostics: $errors")
        assertFalse(file.exists())
    }

    @Test
    @Order(6)
    fun `another open file resolves a class declared only in a file that isn't on disk, in main`() = resolvesFromAnotherFile("main", MAIN_USER)

    @Test
    @Order(7)
    fun `another open file resolves a class declared only in a file that isn't on disk, in test`() = resolvesFromAnotherFile("test", TEST_USER)

    /** The fixture's test source has errors of its own, so this compares against them: the new reference adds none. */
    private fun resolvesFromAnotherFile(sourceSet: String, userFile: String) = runBlocking {
        val file = unsaved(sourceSet)
        val user = project.resolve(userFile)
        val original = Files.readString(user)
        val using = "$original\nfun greeting(): String = fixture.created.Greeter().greet()\n"
        val before = lsp.session { session -> session.diagnostics(user, TextDocument(original)) }
            .filter { it.severity == Severity.ERROR }
        val unopened = lsp.session { session -> session.diagnostics(user, TextDocument(using)) }
            .filter { it.severity == Severity.ERROR }
        assertEquals(before.size + 1, unopened.size, "without Greeter open: $unopened")

        val after = lsp.session { session ->
            session.documentSymbols(file, TextDocument(GREETER))
            session.diagnostics(user, TextDocument(using))
        }.filter { it.severity == Severity.ERROR }

        assertEquals(before, after, "diagnostics: $after")
        assertFalse(file.exists())
    }

    private fun unsaved(sourceSet: String): Path = project.resolve("src/$sourceSet/kotlin/fixture/created/Greeter.kt")

    private companion object {
        const val MAIN_USER = "src/main/kotlin/fixture/Shapes.kt"
        const val TEST_USER = "src/test/kotlin/fixture/ShapesCheck.kt"
        const val GREETER = "package fixture.created\n\nclass Greeter {\n    fun greet(): String = \"hello\"\n}\n"
    }
}
