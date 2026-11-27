package kronstadt.lsp

import kotlinx.coroutines.runBlocking
import kronstadt.core.application.Delete
import kronstadt.core.application.Edit
import kronstadt.core.application.EditResult
import kronstadt.core.application.Replace
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.imports
import kronstadt.workspace.LocalFileStore
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes

/**
 * A multi-file Edit shaped like #209's report: many files each delete a private helper, the last user of two imports,
 * and gain an import from the same package, of which they use 5+ classes, so kotlin-lsp's organize-imports collapses
 * it into a star import, before and after the Edit, and leaves every explicit import of it undecided.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ImportsBatchContractTest {
    @JvmField
    @RegisterExtension
    internal val kotlinLsp = KotlinLspHarness { project -> write(project) }
    private val project get() = kotlinLsp.project
    private val lsp get() = kotlinLsp.lsp
    private val files by lazy { LocalFileStore(project) }

    @Test
    fun `organize-imports collapses the package into a star import both before and after the Edit`() = runBlocking<Unit> {
        assertIs<Readiness.Ready>(lsp.awaitReady(5.minutes))
        lsp.session { session ->
            val file = project.resolve(path(1))
            assertEquals(listOf("fixture.domain.*"), lsp.organizedImports(session, file, TextDocument(test(1)))?.imports?.map { it.text })
            assertEquals(listOf("fixture.domain.*"), lsp.organizedImports(session, file, TextDocument(edited(1)))?.imports?.map { it.text })
        }
    }

    @Test
    fun `kt_edit removes the imports it made unused in every file of the batch, and lists each`() = runBlocking<Unit> {
        assertIs<Readiness.Ready>(lsp.awaitReady(5.minutes))
        val operations = (1..COUNT).flatMap { n ->
            listOf(
                Delete(path(n), "Foo${n}Test/helper"),
                Replace(path(n), "Foo${n}Test/uses", USES_RANGE, imports = listOf("fixture.domain.range")),
            )
        }

        val written = assertIs<EditResult.Written>(Edit(files, lsp)(operations))

        assertEquals((1..COUNT).map(::path), written.files.map { it.file })
        for (file in written.files) {
            assertEquals(listOf("fixture.domain.Position", "fixture.domain.TextRange"), file.importsRemoved, file.file)
            assertEquals(emptyList(), file.importsUnchecked, file.file)
        }
        for (n in 1..COUNT) {
            assertEquals(edited(n).replace("import fixture.domain.Position\nimport fixture.domain.TextRange\n", ""), Files.readString(project.resolve(path(n))))
        }
    }

    private fun path(n: Int) = "src/test/kotlin/fixture/batch/Foo${n}Test.kt"

    private fun write(project: Path) {
        val domain = project.resolve("src/main/kotlin/fixture/domain/Domain.kt")
        Files.createDirectories(domain.parent)
        Files.writeString(domain, DOMAIN)
        for (n in 1..COUNT) {
            val file = project.resolve(path(n))
            Files.createDirectories(file.parent)
            Files.writeString(file, test(n))
        }
    }

    /** Test file [n] before the Edit: `helper` is the only user of Position and TextRange. */
    private fun test(n: Int) = """
        |package fixture.batch
        |
        |import fixture.domain.Alpha
        |import fixture.domain.Beta
        |import fixture.domain.Delta
        |import fixture.domain.Epsilon
        |import fixture.domain.Gamma
        |import fixture.domain.Position
        |import fixture.domain.TextRange
        |
        |class Foo${n}Test {
        |    fun uses(): List<Any> = listOf(Alpha(), Beta(), Gamma(), Delta(), Epsilon(), helper())
        |
        |    private fun helper(): TextRange = TextRange(Position(1), Position(2))
        |}
        |""".trimMargin()

    /** Test file [n] after the Edit's Operations, before it removes the imports they made unused. */
    private fun edited(n: Int) = test(n)
        .replace("import fixture.domain.TextRange\n", "import fixture.domain.TextRange\nimport fixture.domain.range\n")
        .replace("\n\n    private fun helper(): TextRange = TextRange(Position(1), Position(2))\n", "\n")
        .replace("    fun uses(): List<Any> = listOf(Alpha(), Beta(), Gamma(), Delta(), Epsilon(), helper())", "    $USES_RANGE")

    private companion object {
        /** Enough files that checking them all used to outlast the budget they shared (#209). */
        const val COUNT = 13
        const val USES_RANGE = "fun uses(): List<Any> = listOf(Alpha(), Beta(), Gamma(), Delta(), Epsilon(), range(1, 2))"
        val DOMAIN = """
            |package fixture.domain
            |
            |class Position(val line: Int)
            |class TextRange(val start: Position, val end: Position)
            |class Alpha
            |class Beta
            |class Gamma
            |class Delta
            |class Epsilon
            |
            |fun range(start: Int, end: Int): TextRange = TextRange(Position(start), Position(end))
            |""".trimMargin()
    }
}
