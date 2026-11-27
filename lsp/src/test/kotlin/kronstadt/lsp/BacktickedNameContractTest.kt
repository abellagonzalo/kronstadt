package kronstadt.lsp

import kotlinx.coroutines.runBlocking
import kronstadt.core.application.FindUsages
import kronstadt.core.application.FindUsagesResult
import kronstadt.core.application.UsageLine
import kronstadt.core.application.UsagesInFile
import kronstadt.core.application.UsagesInSymbol
import kronstadt.core.application.RenameResult
import kronstadt.core.application.RenameSymbol
import kronstadt.core.application.RenamedFile
import kronstadt.core.domain.Position
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.workspace.LocalFileStore
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.api.extension.RegisterExtension
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Seam 2, #169: a Symbol whose name is in backticks, the fixture's `` `backticked name` ``. Its `selectionRange` starts at
 * the opening backtick (pinned in [KotlinLspContractTest]), where kotlin-lsp refuses to rename; Kronstadt points it
 * inside the name instead. Uses its own copy of the fixture, with a file of Usages added, and so its own kotlin-lsp,
 * because it renames the function.
 */
@Tag("contract")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class BacktickedNameContractTest {
    @JvmField
    @RegisterExtension
    internal val kotlinLsp = KotlinLspHarness { project -> Files.writeString(project.resolve(USAGES), USAGES_TEXT) }
    private val project get() = kotlinLsp.project
    private val lsp get() = kotlinLsp.lsp
    private val files by lazy { LocalFileStore(project) }

    @Test
    @Order(1)
    fun `becomes ready`() = kotlinLsp.waitUntilReady()

    @Test
    @Order(2)
    fun `prepare-rename refuses the opening backtick and takes the name inside it, where references find the same Usages`() = runBlocking<Unit> {
        val file = project.resolve(SPANS)
        val document = TextDocument(Files.readString(file))
        val line = document.text.lines().indexOfFirst { "fun `backticked name`" in it }
        val backtick = Position(line, 4)
        val inside = Position(line, 5)

        lsp.session { session ->
            assertEquals(RenamePreparation.Refused("Rename is not available at this position"), session.prepareRename(file, document, backtick))
            assertEquals(
                RenamePreparation.Renameable(TextRange(backtick, Position(line, 21)), "backticked name"),
                session.prepareRename(file, document, inside),
            )
            val usages = session.references(file, document, inside)
            assertEquals(2, usages.size, "$usages")
            assertEquals(usages, session.references(file, document, backtick))
        }
    }

    /** kotlin-lsp writes the new name as given: in backticks if it has them, bare if not. */
    @Test
    @Order(3)
    fun `rename takes the new name as written, backticks and all`() = runBlocking<Unit> {
        val file = project.resolve(SPANS)
        val document = TextDocument(Files.readString(file))
        val inside = document.positionOf(document.text.indexOf("`backticked name`") + 1)

        lsp.session { session ->
            suspend fun renamedTo(newName: String): String {
                val answer = lsp.reportedRename(session, file, document, inside, newName)
                val edits = assertIs<ReportedRename.Changes>(answer, "$answer").changes.filterIsInstance<ReportedChange.Edited>().single { it.file == file }.edits
                val renamed = edits.sortedByDescending { it.range.start }.fold(document) { text, edit -> text.replace(edit.range, edit.newText) }
                return renamed.line(inside.line).substringBefore("(")
            }
            assertEquals("fun `other name`", renamedTo("`other name`"))
            assertEquals("fun `plain`", renamedTo("`plain`"))
            assertEquals("fun plain", renamedTo("plain"))
        }
        // Nothing is applied.
        assertEquals(document.text, Files.readString(file))
    }

    @Test
    @Order(4)
    fun `kt_find_usages finds a backticked function's Usages`() = runBlocking<Unit> {
        assertEquals(
            FindUsagesResult.Found(
                2, 1, 0,
                listOf(UsagesInFile(USAGES, listOf(UsagesInSymbol("twice", listOf(UsageLine(3, "fun twice(): Int = `backticked name`() + `backticked name`()")))))),
            ),
            FindUsages(files, lsp)("`backticked name`"),
        )
    }

    @Test
    @Order(5)
    fun `kt_rename_symbol renames a backticked function to another backticked name, and its Usages follow`() = runBlocking<Unit> {
        val renamed = RenameSymbol(files, lsp)("`backticked name`", "`other name`")

        assertEquals(
            RenameResult.Renamed("`other name`", listOf(RenamedFile(SPANS, usages = 0), RenamedFile(USAGES, usages = 2))),
            renamed,
        )
        assertContains(Files.readString(project.resolve(SPANS)), "\nfun `other name`(): Int = 3\n")
        assertEquals("package fixture\n\nfun twice(): Int = `other name`() + `other name`()\n", Files.readString(project.resolve(USAGES)))
    }

    @Test
    @Order(6)
    fun `kt_rename_symbol renames a backticked function to a plain name, and its Usages follow`() = runBlocking<Unit> {
        val renamed = RenameSymbol(files, lsp)("`other name`", "plain")

        assertEquals(
            RenameResult.Renamed("plain", listOf(RenamedFile(SPANS, usages = 0), RenamedFile(USAGES, usages = 2))),
            renamed,
        )
        assertContains(Files.readString(project.resolve(SPANS)), "\nfun plain(): Int = 3\n")
        assertEquals("package fixture\n\nfun twice(): Int = plain() + plain()\n", Files.readString(project.resolve(USAGES)))
    }

    private companion object {
        const val SPANS = "src/main/kotlin/fixture/Spans.kt"
        const val USAGES = "src/main/kotlin/fixture/Backticked.kt"
        const val USAGES_TEXT = "package fixture\n\nfun twice(): Int = `backticked name`() + `backticked name`()\n"
    }
}
