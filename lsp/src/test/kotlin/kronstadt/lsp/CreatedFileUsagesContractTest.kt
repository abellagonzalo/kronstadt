@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.application.Create
import kronstadt.core.application.Edit
import kronstadt.core.application.EditResult
import kronstadt.core.application.FindUsages
import kronstadt.core.application.FindUsagesResult
import kronstadt.core.application.RenameResult
import kronstadt.core.application.RenameSymbol
import kronstadt.core.application.RenamedFile
import kronstadt.core.domain.Readiness
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
 * Seam 2, #92: does kotlin-lsp see a file that kt_edit's `create` wrote after kotlin-lsp imported the project, so that
 * kt_find_usages lists its Usages and kt_rename_symbol renames them, right away, before its file watcher could have
 * caught up?
 *
 * Each test creates the file through [Edit], the path kt_edit takes: Validation opens it, Write-back writes it and tells
 * the session it was written back. Then, in the same project, it asks for the Usages of an existing property and
 * renames it. Uses its own copy of the fixture, and so its own kotlin-lsp, because it renames fixture properties.
 *
 * Outcome (kotlin-lsp 271.2085.0): yes, in an existing package and in a new package directory, with no
 * `workspace/didChangeWatchedFiles`. It holds even when the written-back document isn't retained but closed at the end
 * of the Edit: the `didOpen` and `didSave` kotlin-lsp got are enough, unlike the file
 * #92 reports as only written to disk.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CreatedFileUsagesContractTest {
    private lateinit var project: Path
    private lateinit var indexDir: Path
    private lateinit var lsp: LspCodeIntelligence
    private lateinit var files: LocalFileStore

    @BeforeAll
    fun start() {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :lsp:contractTest" })
        project = Files.createTempDirectory("kronstadt-fixture-created").toRealPath()
        fixture.copyToRecursively(project, followLinks = false, overwrite = true)
        val command = (System.getenv("KRONSTADT_LANGUAGE_SERVER") ?: "kotlin-lsp --stdio").trim().split(Regex("\\s+"))
        indexDir = Files.createTempDirectory("kronstadt-index").toRealPath().resolve("kotlin-server")
        lsp = runBlocking { LspCodeIntelligence.start(command, project, indexDir) }
        files = LocalFileStore(project)
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
    fun `a file kt_edit created in an existing package has its Usages found and renamed`() = runBlocking<Unit> {
        val created = "src/main/kotlin/fixture/Reader.kt"
        create(created, "package fixture\n\nfun read(): Int = topLevelVal + 1\n")

        assertEquals(
            """
            |1 usage in 1 file
            |$created
            |  read
            |    L3: fun read(): Int = topLevelVal + 1
            """.trimMargin(),
            assertIs<FindUsagesResult.Found>(FindUsages(files, lsp)("topLevelVal")).text,
        )

        val renamed = RenameSymbol(files, lsp)("topLevelVal", "renamedVal")

        assertEquals(
            RenameResult.Renamed("renamedVal", listOf(RenamedFile(MUTABILITY, usages = 0), RenamedFile(created, usages = 1))),
            renamed,
        )
        assertEquals("package fixture\n\nfun read(): Int = renamedVal + 1\n", Files.readString(project.resolve(created)))
    }

    @Test
    @Order(3)
    fun `a file kt_edit created in a new package directory has its Usages found and renamed`() = runBlocking<Unit> {
        val created = "src/main/kotlin/fixture/fresh/Writer.kt"
        create(created, "package fixture.fresh\n\nimport fixture.topLevelVar\n\nfun write(): Int = topLevelVar * 2\n")

        assertEquals(
            """
            |1 usage in 1 file (+1 import)
            |$created
            |  write
            |    L5: fun write(): Int = topLevelVar * 2
            """.trimMargin(),
            assertIs<FindUsagesResult.Found>(FindUsages(files, lsp)("topLevelVar")).text,
        )

        val renamed = RenameSymbol(files, lsp)("topLevelVar", "renamedVar")

        assertEquals(
            RenameResult.Renamed(
                "renamedVar",
                listOf(RenamedFile(MUTABILITY, usages = 0), RenamedFile(created, usages = 1, imports = 1)),
            ),
            renamed,
        )
        assertEquals(
            "package fixture.fresh\n\nimport fixture.renamedVar\n\nfun write(): Int = renamedVar * 2\n",
            Files.readString(project.resolve(created)),
        )
    }

    private suspend fun create(file: String, text: String) {
        val result = Edit(files, lsp)(Create(file, text))
        assertIs<EditResult.Written>(result, "$result")
    }

    private companion object {
        const val MUTABILITY = "src/main/kotlin/fixture/Mutability.kt"
    }
}
