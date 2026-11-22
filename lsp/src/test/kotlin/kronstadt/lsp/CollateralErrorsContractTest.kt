@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.application.CollateralErrors
import kronstadt.core.application.Edit
import kronstadt.core.application.EditResult
import kronstadt.core.application.Replace
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
 * #84, the shape #38 hit: an Edit changes a function's return type, Validation passes for the file it touched, and a
 * caller in another file no longer compiles. Does kt_edit, through real kotlin-lsp, report that caller's error as a
 * Collateral Error, and write the Edit anyway? Uses its own copy of the fixture, and so its own kotlin-lsp, because it
 * writes to fixture files.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CollateralErrorsContractTest {
    private lateinit var project: Path
    private lateinit var indexDir: Path
    private lateinit var lsp: LspCodeIntelligence
    private lateinit var files: LocalFileStore

    @BeforeAll
    fun start() {
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :lsp:contractTest" })
        project = Files.createTempDirectory("kronstadt-fixture-collateral").toRealPath()
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
    fun `an Edit that only changes a body reports no Collateral Errors`() = runBlocking<Unit> {
        val result = Edit(files, lsp)(Replace(SHAPES, "Calculator/parse(String)", "fun parse(text: String): Int = text.trim().toInt() * scale"))

        assertEquals(CollateralErrors(), assertIs<EditResult.Written>(result, "$result").collateral)
    }

    @Test
    @Order(3)
    fun `changing a return type reports the caller it broke in another file, and writes the Edit`() = runBlocking<Unit> {
        val result = Edit(files, lsp)(Replace(SHAPES, "Calculator/parse(String)", "fun parse(text: String): String = text.trim()"))

        val collateral = assertIs<EditResult.Written>(result, "$result").collateral
        assertEquals(null, collateral.unfinished, "$collateral")
        assertEquals(listOf(Triple(SHAPES_CHECK, "checkShapes", 8)), collateral.errors.map { Triple(it.file, it.symbol, it.line) }, "$collateral")
        assertEquals(0, collateral.moreErrors + collateral.filesUnchecked, "$collateral")
        assertEquals(true, "fun parse(text: String): String = text.trim()" in Files.readString(project.resolve(SHAPES)))
    }

    private companion object {
        const val SHAPES = "src/main/kotlin/fixture/Shapes.kt"
        const val SHAPES_CHECK = "src/test/kotlin/fixture/ShapesCheck.kt"
    }
}
