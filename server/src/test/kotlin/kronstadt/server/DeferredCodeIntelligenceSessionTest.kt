@file:OptIn(ExperimentalCoroutinesApi::class) // runCurrent

package kronstadt.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.ImportUsage
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/** Sessions opened while the language server may still be starting. */
class DeferredCodeIntelligenceSessionTest {
    private val file = Path.of("/project/A.kt")
    private val document = TextDocument("class A\n")

    @Test
    fun `a session that analyses nothing neither waits for startup nor fails with its error`() = runTest {
        val starting = DeferredCodeIntelligence(CompletableDeferred())
        val failed = DeferredCodeIntelligence(CompletableDeferred(Result.failure(IllegalStateException("no kotlin-lsp"))))

        assertEquals("answered", starting.session { "answered" })
        assertEquals("answered", failed.session { "answered" })
    }

    @Test
    fun `a session's first analysis waits for startup, then everything runs in one started session that ends with it`() = runTest {
        val startup = CompletableDeferred<Result<CodeIntelligence>>()
        val started = RecordingIntelligence()
        val call = async {
            DeferredCodeIntelligence(startup).session { session ->
                session.documentSymbols(file, document)
                session.diagnostics(file, document)
            }
        }
        runCurrent()
        assertFalse(call.isCompleted)

        startup.complete(Result.success(started))
        call.await()

        assertEquals(listOf("begin", "symbols A.kt", "diagnostics A.kt", "end"), started.events)
    }

    @Test
    fun `a session's analysis after a failed startup throws the startup error`() = runTest {
        val failed = DeferredCodeIntelligence(CompletableDeferred(Result.failure(IllegalStateException("no kotlin-lsp"))))

        val error = assertFailsWith<IllegalStateException> { failed.session { it.documentSymbols(file, document) } }
        assertEquals("no kotlin-lsp", error.message)
    }

    @Test
    fun `a written-back file with nothing analysed yet neither waits for startup nor fails with its error`() = runTest {
        val starting = DeferredCodeIntelligence(CompletableDeferred())
        val failed = DeferredCodeIntelligence(CompletableDeferred(Result.failure(IllegalStateException("no kotlin-lsp"))))

        val call = async { starting.session { it.writtenBack(file, document) } }
        runCurrent()
        assertTrue(call.isCompleted)

        failed.session { it.writtenBack(file, document) }
    }

    @Test
    fun `a written-back file is told to the language server's session once startup has finished`() = runTest {
        val started = RecordingIntelligence()

        DeferredCodeIntelligence(CompletableDeferred(Result.success(started))).session { it.writtenBack(file, document) }

        assertEquals(listOf("begin", "written back A.kt", "end"), started.events)
    }

    @Test
    fun `a written-back file is told to the session the first analysis opened during startup`() = runTest {
        val startup = CompletableDeferred<Result<CodeIntelligence>>()
        val started = RecordingIntelligence()
        val call = async {
            DeferredCodeIntelligence(startup).session { session ->
                session.documentSymbols(file, document)
                session.writtenBack(file, document)
            }
        }
        runCurrent()

        startup.complete(Result.success(started))
        call.await()

        assertEquals(listOf("begin", "symbols A.kt", "written back A.kt", "end"), started.events)
    }
}

/** Records when its sessions begin and end, and what they analyse. */
private class RecordingIntelligence : CodeIntelligence {
    val events = mutableListOf<String>()

    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T {
        events += "begin"
        try {
            return block(object : AnalysisSession {
                override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> =
                    emptyList<CodeSymbol>().also { events += "symbols ${file.fileName}" }

                override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> =
                    emptyList<Diagnostic>().also { events += "diagnostics ${file.fileName}" }

                override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> = emptyList()
                override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> = emptyList()
                override suspend fun prepareRename(file: Path, document: TextDocument, position: Position) = RenamePreparation.Refused("unsupported")
                override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String) = Rename.Refused("unsupported")
                override suspend fun unusedImports(file: Path, document: TextDocument, among: Set<ImportDirective>, budget: Duration) = ImportUsage.undecided(among)
                override suspend fun writtenBack(file: Path, document: TextDocument) {
                    events += "written back ${file.fileName}"
                }
            })
        } finally {
            events += "end"
        }
    }

    override suspend fun workspaceSymbols(query: String): SymbolSearch = SymbolSearch(emptyList())
    override suspend fun awaitReady(timeout: Duration): Readiness = Readiness.Ready
}
