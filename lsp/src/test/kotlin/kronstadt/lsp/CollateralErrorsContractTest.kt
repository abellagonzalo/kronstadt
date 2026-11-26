package kronstadt.lsp

import kotlinx.coroutines.runBlocking
import kronstadt.core.application.CollateralErrors
import kronstadt.core.application.Edit
import kronstadt.core.application.EditResult
import kronstadt.core.application.InsertAfter
import kronstadt.core.application.Replace
import kronstadt.workspace.LocalFileStore
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * #84, the shape #38 hit: an Edit changes a function's return type, Validation passes for the file it touched, and a
 * caller in another file no longer compiles. Does kt_edit, through real kotlin-lsp, report that caller's error as a
 * Collateral Error, and write the Edit anyway? Uses its own copy of the fixture, and so its own kotlin-lsp, because it
 * writes to fixture files. Its copy also gets a sealed interface, and a `when` over it in another file (#133).
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class CollateralErrorsContractTest {
    @JvmField
    @RegisterExtension
    internal val kotlinLsp = KotlinLspHarness { project ->
        Files.createDirectories(project.resolve(OUTCOME).parent)
        Files.writeString(project.resolve(OUTCOME), "package fixture.outcome\n\nsealed interface Outcome {\n    data object Ok : Outcome\n}\n")
        Files.writeString(
            project.resolve(DESCRIBE_OUTCOME),
            "package fixture.outcome\n\nfun describe(outcome: Outcome): String = when (outcome) {\n    Outcome.Ok -> \"ok\"\n}\n",
        )
    }
    private val project get() = kotlinLsp.project
    private val lsp get() = kotlinLsp.lsp
    private val files by lazy { LocalFileStore(project) }

    @Test
    @Order(1)
    fun `becomes ready`() = kotlinLsp.waitUntilReady()

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

    /** #133: a new subtype of a sealed type changes no existing header, yet leaves a `when` over it non-exhaustive. */
    @Test
    @Order(4)
    fun `adding a subtype nested in a sealed interface reports the when it made non-exhaustive in another file`() = runBlocking<Unit> {
        val result = Edit(files, lsp)(InsertAfter(OUTCOME, "Outcome/Ok", "data object Failed : Outcome"))

        val collateral = assertIs<EditResult.Written>(result, "$result").collateral
        assertEquals(null, collateral.unfinished, "$collateral")
        assertEquals(listOf(Triple(DESCRIBE_OUTCOME, "describe", 3)), collateral.errors.map { Triple(it.file, it.symbol, it.line) }, "$collateral")
        assertEquals(true, "data object Failed : Outcome" in Files.readString(project.resolve(OUTCOME)))
    }

    private companion object {
        const val OUTCOME = "src/main/kotlin/fixture/outcome/Outcome.kt"
        const val DESCRIBE_OUTCOME = "src/main/kotlin/fixture/outcome/DescribeOutcome.kt"
        const val SHAPES = "src/main/kotlin/fixture/Shapes.kt"
        const val SHAPES_CHECK = "src/test/kotlin/fixture/ShapesCheck.kt"
    }
}
