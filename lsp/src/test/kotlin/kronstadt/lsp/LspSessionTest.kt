package kronstadt.lsp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.TextDocument
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.seconds

/** Sessions in the lsp adapter, against a stubbed language server that records what it was told. */
class LspSessionTest {
    private val file = Path.of("/project/src/A.kt")
    private val disk = TextDocument("class A\n")
    private val written = TextDocument("class B\n")
    private val outside = TextDocument("class C\n")

    private val server = RecordingServer()
    private val lsp = LspCodeIntelligence.connected(LiveProcess(), server, requestTimeout = 5.seconds)

    @Test
    fun `a session closes every document it opened when it ends`() = runTest {
        lsp.session { session ->
            session.documentSymbols(file, disk)
            session.diagnostics(file, disk)
        }

        assertEquals(listOf("open A.kt v1", "close A.kt"), server.events)
    }

    @Test
    fun `overlapping sessions on the same file close it once, after both ended`() = runTest {
        val firstOpened = CompletableDeferred<Unit>()
        val secondEnded = CompletableDeferred<Unit>()
        val first = async {
            lsp.session { session ->
                session.documentSymbols(file, disk)
                firstOpened.complete(Unit)
                secondEnded.await()
                server.events.toList()
            }
        }
        firstOpened.await()
        lsp.session { session -> session.documentSymbols(file, disk) }
        secondEnded.complete(Unit)

        assertEquals(listOf("open A.kt v1"), first.await())
        assertEquals(listOf("open A.kt v1", "close A.kt"), server.events)
    }

    @Test
    fun `a session ended by an exception still closes its documents`() = runTest {
        assertFailsWith<IllegalStateException> {
            lsp.session { session ->
                session.documentSymbols(file, disk)
                error("tool failed")
            }
        }

        assertEquals(listOf("open A.kt v1", "close A.kt"), server.events)
    }

    @Test
    fun `a cancelled session still closes its documents`() = runTest {
        val opened = CompletableDeferred<Unit>()
        val call = launch {
            lsp.session { session ->
                session.documentSymbols(file, disk)
                opened.complete(Unit)
                awaitCancellation()
            }
        }
        opened.await()
        call.cancelAndJoin()

        assertEquals(listOf("open A.kt v1", "close A.kt"), server.events)
    }

    @Test
    fun `a written document stays open past its session while the disk holds what was written`() = runTest {
        lsp.session { session ->
            session.documentSymbols(file, disk)
            session.diagnostics(file, written)
            session.writtenBack(file, written)
        }
        lsp.session { session -> session.documentSymbols(file, written) }

        assertEquals(listOf("open A.kt v1", "change A.kt v2", "save A.kt"), server.events)
    }

    @Test
    fun `the first session that reads other text from disk syncs the written document and closes it`() = runTest {
        lsp.session { session ->
            session.documentSymbols(file, disk)
            session.diagnostics(file, written)
            session.writtenBack(file, written)
        }
        lsp.session { session -> session.documentSymbols(file, outside) }

        assertEquals(listOf("open A.kt v1", "change A.kt v2", "save A.kt", "change A.kt v3", "close A.kt"), server.events)
    }
}

/** A language server that answers every analysis with nothing and records document notifications, in order. */
private class RecordingServer : LanguageServer {
    val events = mutableListOf<String>()

    private fun name(uri: String) = uri.substringAfterLast('/')

    private val documents = object : TextDocumentService {
        override fun didOpen(params: DidOpenTextDocumentParams) {
            events += "open ${name(params.textDocument.uri)} v${params.textDocument.version}"
        }

        override fun didChange(params: DidChangeTextDocumentParams) {
            events += "change ${name(params.textDocument.uri)} v${params.textDocument.version}"
        }

        override fun didClose(params: DidCloseTextDocumentParams) {
            events += "close ${name(params.textDocument.uri)}"
        }

        override fun didSave(params: DidSaveTextDocumentParams) {
            events += "save ${name(params.textDocument.uri)}"
        }

        override fun documentSymbol(params: DocumentSymbolParams): CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>> =
            CompletableFuture.completedFuture(emptyList())

        override fun diagnostic(params: DocumentDiagnosticParams): CompletableFuture<DocumentDiagnosticReport> =
            CompletableFuture.completedFuture(DocumentDiagnosticReport(RelatedFullDocumentDiagnosticReport(emptyList())))
    }

    override fun getTextDocumentService(): TextDocumentService = documents
    override fun getWorkspaceService(): WorkspaceService = error("not stubbed")
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = error("not stubbed")
    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.completedFuture(null)
    override fun exit() {}
}

/** A language server process that never exits. */
private class LiveProcess : Process() {
    override fun getOutputStream(): OutputStream = OutputStream.nullOutputStream()
    override fun getInputStream(): InputStream = InputStream.nullInputStream()
    override fun getErrorStream(): InputStream = InputStream.nullInputStream()
    override fun waitFor(): Int = error("never exits")
    override fun exitValue(): Int = throw IllegalThreadStateException("still running")
    override fun destroy() {}
    override fun onExit(): CompletableFuture<Process> = CompletableFuture()
}
