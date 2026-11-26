package kronstadt.lsp

import kotlinx.coroutines.runBlocking
import kronstadt.core.application.Create
import kronstadt.core.application.Edit
import kronstadt.core.application.EditResult
import kronstadt.core.application.ValidationFailure
import kronstadt.core.domain.Severity
import kronstadt.core.domain.TextDocument
import kronstadt.workspace.LocalFileStore
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

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
    @JvmField
    @RegisterExtension
    internal val kotlinLsp = KotlinLspHarness()
    private val project get() = kotlinLsp.project
    private val lsp get() = kotlinLsp.lsp

    @Test
    @Order(1)
    fun `becomes ready`() = kotlinLsp.awaitReady()

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

    private fun resolvesFromAnotherFile(sourceSet: String, userFile: String) = runBlocking {
        val file = unsaved(sourceSet)
        val user = project.resolve(userFile)
        val original = Files.readString(user)
        val using = "$original\nfun greeting(): String = fixture.created.Greeter().greet()\n"
        val before = lsp.session { session -> session.diagnostics(user, TextDocument(original)) }
            .filter { it.severity == Severity.ERROR }
        assertEquals(emptyList(), before, "before: $before")
        val unopened = lsp.session { session -> session.diagnostics(user, TextDocument(using)) }
            .filter { it.severity == Severity.ERROR }
        assertEquals(1, unopened.size, "without Greeter open: $unopened")

        val after = lsp.session { session ->
            session.documentSymbols(file, TextDocument(GREETER))
            session.diagnostics(user, TextDocument(using))
        }.filter { it.severity == Severity.ERROR }

        assertEquals(emptyList(), after, "diagnostics: $after")
        assertFalse(file.exists())
    }

    @Test
    @Order(8)
    fun `kt_edit rejects a created file that doesn't compile, creating no directory`() = runBlocking {
        val result = Edit(LocalFileStore(project), lsp)(Create("src/main/kotlin/fixture/made/Broken.kt", "package fixture.made\n\nval broken: Int = \"x\"\n"))

        val errors = assertIs<ValidationFailure.Rejected>(assertIs<EditResult.ValidationFailed>(result, "$result").failure).newErrors
        assertEquals(listOf(3), errors.map { it.line }, "$errors")
        assertFalse(project.resolve("src/main/kotlin/fixture/made").exists())
    }

    @Test
    @Order(9)
    fun `kt_edit creates a main class and a test that uses it in one Edit`() = runBlocking {
        val result = Edit(LocalFileStore(project), lsp)(
            listOf(
                Create("src/main/kotlin/fixture/made/Greeter.kt", GREETER.replace("fixture.created", "fixture.made")),
                Create("src/test/kotlin/fixture/made/GreeterCheck.kt", "package fixture.made\n\nfun greets(): Boolean = Greeter().greet() == \"hello\"\n"),
            ),
        )

        val written = assertIs<EditResult.Written>(result, "$result").files
        assertEquals(listOf(listOf("Greeter"), listOf("greets")), written.map { file -> file.symbols.map { it.path } })
        assertEquals(listOf(true, true), written.map { it.created })
        assertEquals(GREETER.replace("fixture.created", "fixture.made"), Files.readString(project.resolve("src/main/kotlin/fixture/made/Greeter.kt")))
    }

    private fun unsaved(sourceSet: String): Path = project.resolve("src/$sourceSet/kotlin/fixture/created/Greeter.kt")

    private companion object {
        const val MAIN_USER = "src/main/kotlin/fixture/Shapes.kt"
        const val TEST_USER = "src/test/kotlin/fixture/ShapesCheck.kt"
        const val GREETER = "package fixture.created\n\nclass Greeter {\n    fun greet(): String = \"hello\"\n}\n"
    }
}
