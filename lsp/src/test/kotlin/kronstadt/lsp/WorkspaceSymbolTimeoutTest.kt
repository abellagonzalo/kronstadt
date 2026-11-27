package kronstadt.lsp

import kotlinx.coroutines.runBlocking
import kronstadt.core.domain.SymbolSearch
import org.eclipse.lsp4j.DidChangeConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidChangeWatchedFilesParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializeResult
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import org.eclipse.lsp4j.services.WorkspaceService
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A `workspace/symbol` search that takes too long, against a stubbed server spoken to over JSON-RPC, as kotlin-lsp is:
 * it gives up after its own timeout, not the general request timeout, and cancels the request at the server (#211).
 */
class WorkspaceSymbolTimeoutTest {
    private val server = SlowSymbolServer()
    private val lsp = LspCodeIntelligence.connected(LiveProcess(), connect(server), requestTimeout = 120.seconds, workspaceSymbolTimeout = TIMEOUT)

    @Test
    fun `a search that outlasts its timeout comes back timed out, and is cancelled at the server`() = runBlocking {
        val start = TimeSource.Monotonic.markNow()
        val search = lsp.workspaceSymbols("toString")
        val elapsed = start.elapsedNow()

        assertEquals(SymbolSearch(emptyList(), SymbolSearch.Completeness.TIMED_OUT), search)
        assertTrue(elapsed >= TIMEOUT && elapsed < TIMEOUT + MARGIN, "returned after $elapsed")
        // lsp4j cancels the server's future when `$/cancelRequest` arrives.
        assertTrue(runCatching { server.pending.get(MARGIN.inWholeMilliseconds, TimeUnit.MILLISECONDS) }.isFailure)
        assertTrue(server.pending.isCancelled, "the server was never told to cancel")
    }

    @Test
    fun `a search answered in time comes back as the server answered it`() = runBlocking {
        assertEquals(SymbolSearch(emptyList(), SymbolSearch.Completeness.COMPLETE), lsp.workspaceSymbols("emitKdoc"))
    }

    /** A client connected to [server] through pipes, so requests and cancellations go over JSON-RPC. */
    private fun connect(server: LanguageServer): LanguageServer {
        val toServer = PipedOutputStream()
        val toClient = PipedOutputStream()
        LSPLauncher.createServerLauncher(server, PipedInputStream(toServer), toClient).startListening()
        val client = LSPLauncher.createClientLauncher(SilentClient(), PipedInputStream(toClient), toServer)
        client.startListening()
        return client.remoteProxy
    }

    private companion object {
        val TIMEOUT = 1.seconds
        val MARGIN = 2.seconds
    }
}

/** Answers `workspace/symbol` for `toString` never, keeping the request in [pending]; any other query with nothing. */
private class SlowSymbolServer : LanguageServer {
    val pending = CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>>()

    override fun getWorkspaceService(): WorkspaceService = object : WorkspaceService {
        override fun symbol(params: WorkspaceSymbolParams): CompletableFuture<Either<List<SymbolInformation>, List<WorkspaceSymbol>>> =
            if (params.query == "toString") pending else CompletableFuture.completedFuture(Either.forRight(emptyList()))

        override fun didChangeConfiguration(params: DidChangeConfigurationParams) {}
        override fun didChangeWatchedFiles(params: DidChangeWatchedFilesParams) {}
    }

    override fun getTextDocumentService(): TextDocumentService = object : TextDocumentService {
    override fun didOpen(params: DidOpenTextDocumentParams) {}
    override fun didChange(params: DidChangeTextDocumentParams) {}
    override fun didClose(params: DidCloseTextDocumentParams) {}
    override fun didSave(params: DidSaveTextDocumentParams) {}
}
    override fun initialize(params: InitializeParams): CompletableFuture<InitializeResult> = error("not stubbed")
    override fun shutdown(): CompletableFuture<Any> = CompletableFuture.completedFuture(null)
    override fun exit() {}
}

/** A client that ignores whatever the server tells it. */
private class SilentClient : LanguageClient {
    override fun telemetryEvent(`object`: Any?) {}
    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {}
    override fun showMessage(messageParams: MessageParams) {}
    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem?> =
        CompletableFuture.completedFuture(null)
    override fun logMessage(message: MessageParams) {}
}
