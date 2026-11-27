package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.ImportUsage
import kronstadt.core.domain.Position
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.domain.imports
import kronstadt.core.port.AnalysisSession
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Which imports kotlin-lsp's organize-imports answer shows unused: those it dropped, and those a new star import covers
 * that removing adds no error (#81).
 */
class UnusedImportsTest {
    private val file = Path.of("/project/src/A.kt")

    /** C1 is used and C2 isn't, but organize-imports collapses both into `c.*`; `b.Bravo` it drops. */
    private val text = TextDocument("package a\n\nimport b.Bravo\nimport c.C1\nimport c.C2\n\nfun greet() = C1\n")
    private val organized = text.importsOrganizedIn(TextDocument("package a\n\nimport c.*\n\nfun greet() = C1\n"))

    private fun unresolved(line: Int) =
        Diagnostic(TextRange(Position(line, 14), Position(line, 16)), Severity.ERROR, "Unresolved reference 'C1'.", "UNRESOLVED_REFERENCE")

    /** Removing c.C1 from [text], alone or with others, leaves C1 unresolved; removing anything else adds no error. */
    private val diagnosticsByText = mapOf(
        "package a\n\nimport b.Bravo\n\nfun greet() = C1\n" to listOf(unresolved(4)),
        "package a\n\nimport b.Bravo\nimport c.C2\n\nfun greet() = C1\n" to listOf(unresolved(5)),
    )

    /** Diagnostics as [diagnosticsByText] gives, each pull taking [delay]; a pull of a text in [failing] fails. */
    private class FakeSession(
        private val diagnosticsByText: Map<String, List<Diagnostic>>,
        private val failing: Set<String> = emptySet(),
        private val delay: Duration = Duration.ZERO,
    ) : AnalysisSession {
        val pulled = mutableListOf<String>()

        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> {
            delay(delay)
            pulled += document.text
            check(document.text !in failing) { "diagnostics failed" }
            return diagnosticsByText[document.text].orEmpty()
        }

        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> = error("not used")
        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> = error("not used")
        override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> = error("not used")
        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation = error("not used")
        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename = error("not used")
        override suspend fun unusedImports(file: Path, document: TextDocument, among: Set<ImportDirective>, budget: Duration): ImportUsage =
            error("not used")
        override suspend fun writtenBack(file: Path, document: TextDocument) = error("not used")
    }

    private fun Set<ImportDirective>.texts() = map { it.text }.sorted()

    /** The unused imports' texts, then the undecided ones'. */
    private fun ImportUsage.texts() = unused.texts() to undecided.texts()

    @Test
    fun `a dropped import is unused, and an undecided one only if removing it adds no error, tried together then each alone`() = runTest {
        val session = FakeSession(diagnosticsByText)

        val usage = session.unusedPerOrganizeImports(file, text, organized, text.imports.toSet(), 15.seconds)

        // c.C1, whose removal adds an error, is used.
        assertEquals(listOf("b.Bravo", "c.C2") to emptyList(), usage.texts())
        // The text itself, both undecided imports removed together, then each alone.
        assertEquals(4, session.pulled.size)
    }

    @Test
    fun `only the imports asked about are judged`() = runTest {
        val session = FakeSession(diagnosticsByText)
        val c2 = text.imports.filter { it.text == "c.C2" }.toSet()

        assertEquals(listOf("c.C2") to emptyList(), session.unusedPerOrganizeImports(file, text, organized, c2, 15.seconds).texts())
        assertEquals(emptyList<String>() to emptyList(), session.unusedPerOrganizeImports(file, text, organized, emptySet(), 15.seconds).texts())
    }

    @Test
    fun `without a diagnostics answer an import a new star covers stays undecided, and a dropped one is still unused`() = runTest {
        val failing = setOf("package a\n\nimport b.Bravo\n\nfun greet() = C1\n")
        val session = FakeSession(diagnosticsByText, failing = failing)

        assertEquals(
            listOf("b.Bravo") to listOf("c.C1", "c.C2"),
            session.unusedPerOrganizeImports(file, text, organized, text.imports.toSet(), 15.seconds).texts(),
        )
    }

    @Test
    fun `without an organize-imports answer every import asked about is undecided (#193)`() = runTest {
        val session = FakeSession(diagnosticsByText)

        val usage = session.unusedPerOrganizeImports(file, text, null, text.imports.toSet(), 15.seconds)

        assertEquals(emptyList<String>() to listOf("b.Bravo", "c.C1", "c.C2"), usage.texts())
        assertEquals(emptyList(), session.pulled)
    }

    @Test
    fun `once the budget runs out the imports a new star covers that aren't settled stay undecided`() = runTest {
        // 1s per pull: the text, then both together (an error), then c.C1 alone, which the budget cuts off.
        val slow = FakeSession(diagnosticsByText, delay = 1.seconds)

        assertEquals(
            listOf("b.Bravo") to listOf("c.C1", "c.C2"),
            slow.unusedPerOrganizeImports(file, text, organized, text.imports.toSet(), 2.5.seconds).texts(),
        )
        assertEquals(
            listOf("b.Bravo") to listOf("c.C1", "c.C2"),
            FakeSession(diagnosticsByText).unusedPerOrganizeImports(file, text, organized, text.imports.toSet(), Duration.ZERO).texts(),
        )
    }

    @Test
    fun `what the removal check settled before the budget ran out stands`() = runTest {
        // The text, both together (an error) and c.C1 alone (an error) fit in; c.C2 alone doesn't.
        val slow = FakeSession(diagnosticsByText, delay = 1.seconds)

        assertEquals(
            listOf("b.Bravo") to listOf("c.C2"),
            slow.unusedPerOrganizeImports(file, text, organized, text.imports.toSet(), 3.5.seconds).texts(),
        )
    }
}
