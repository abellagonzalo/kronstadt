package kronstadt.lsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.CodeIntelligence
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentClientCapabilities
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.UnregistrationParams
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WindowClientCapabilities
import org.eclipse.lsp4j.WorkDoneProgressBegin
import org.eclipse.lsp4j.WorkDoneProgressCreateParams
import org.eclipse.lsp4j.WorkDoneProgressEnd
import org.eclipse.lsp4j.WorkspaceClientCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.services.JsonNotification
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * [CodeIntelligence] backed by a kotlin-lsp child process (ADR 0001), bound to a single project root.
 *
 * One instance per worktree: never share a language server between worktrees, or their ASTs collide.
 * Documents are synced with full-content `didOpen`/`didChange` notifications, so the server always analyses
 * exactly the text the use case handed us.
 */
public class LspCodeIntelligence private constructor(
    private val process: Process,
    private val server: LanguageServer,
    private val requestTimeout: Duration,
    private val readiness: ReadinessTracker,
    /** What kotlin-lsp reported as `serverInfo.version` at `initialize`, if anything. */
    public val serverVersion: String?,
) : CodeIntelligence, AutoCloseable {

    /** [users] counts the calls that analysed this document and haven't closed it yet; read tools run concurrently. */
    private data class OpenDocument(val version: Int, val text: String, val users: Int)

    private val syncLock = Mutex()
    private val openDocuments = mutableMapOf<String, OpenDocument>()

    override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> {
        val uri = LspMapping.uri(file)
        sync(uri, document)
        val result = request { server.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))) }
        return LspMapping.documentSymbols(result)
    }

    override suspend fun closeDocument(file: Path) {
        val uri = LspMapping.uri(file)
        syncLock.withLock {
            val open = openDocuments[uri] ?: return@withLock
            if (open.users > 1) {
                openDocuments[uri] = open.copy(users = open.users - 1)
            } else {
                openDocuments.remove(uri)
                server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            }
        }
    }

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> {
        val result = request { server.workspaceService.symbol(WorkspaceSymbolParams(query)) }
        return LspMapping.workspaceSymbols(result)
    }

    override suspend fun awaitReady(timeout: Duration): Readiness = readiness.awaitReady(timeout)

    private suspend fun sync(uri: String, document: TextDocument) = syncLock.withLock {
        val open = openDocuments[uri]
        when {
            open == null -> {
                server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, document.text)))
                openDocuments[uri] = OpenDocument(1, document.text, users = 1)
            }
            open.text != document.text -> {
                val version = open.version + 1
                server.textDocumentService.didChange(
                    DidChangeTextDocumentParams(
                        VersionedTextDocumentIdentifier(uri, version),
                        listOf(TextDocumentContentChangeEvent(document.text)),
                    ),
                )
                openDocuments[uri] = OpenDocument(version, document.text, open.users + 1)
            }
            else -> openDocuments[uri] = open.copy(users = open.users + 1)
        }
    }

    private suspend fun <T> request(call: () -> CompletableFuture<T>): T = awaitAlive(process, requestTimeout, call())

    override fun close() {
        runCatching {
            server.shutdown().get(5, TimeUnit.SECONDS)
            server.exit()
        }
        if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly()
    }

    public companion object {
        /**
         * Spawns [command] (e.g. `kotlin-lsp --stdio`) in [projectRoot] and completes the LSP handshake.
         * The server's stderr is inherited, which is safe: MCP only uses our stdout.
         * Returns as soon as `initialize` answers; the import and indexing go on in the background (see [awaitReady]).
         */
        public suspend fun start(
            command: List<String>,
            projectRoot: Path,
            requestTimeout: Duration = 120.seconds,
        ): LspCodeIntelligence {
            val process = ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val readiness = ReadinessTracker()
            val launcher = LSPLauncher.createClientLauncher(
                KronstadtLanguageClient(readiness),
                process.inputStream,
                process.outputStream,
            )
            launcher.startListening()
            val server = launcher.remoteProxy

            val serverVersion = try {
                awaitAlive(process, requestTimeout, server.initialize(initializeParams(projectRoot))).serverInfo?.version
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process.destroyForcibly()
                throw IllegalStateException("Language server '${command.joinToString(" ")}' failed to initialize: ${e.message}", e)
            }
            KotlinLspVersion.mismatchWarning(serverVersion)?.let(System.err::println)
            server.initialized(InitializedParams())
            return LspCodeIntelligence(process, server, requestTimeout, readiness, serverVersion)
        }

        /**
         * Awaits [future], but fails fast if the language server process dies (otherwise lsp4j would leave the
         * request pending until the timeout).
         */
        private suspend fun <T> awaitAlive(process: Process, timeout: Duration, future: CompletableFuture<T>): T =
            withTimeout(timeout) {
                CompletableFuture.anyOf(future, process.onExit()).await()
                if (!future.isDone) {
                    future.cancel(true)
                    throw IllegalStateException("language server exited with code ${process.exitValue()}")
                }
                future.await()
            }

        private fun initializeParams(projectRoot: Path) = InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            clientInfo = org.eclipse.lsp4j.ClientInfo("kronstadt")
            workspaceFolders = listOf(WorkspaceFolder(LspMapping.uri(projectRoot), projectRoot.fileName.toString()))
            capabilities = ClientCapabilities().apply {
                // Makes kotlin-lsp report its import and indexing as `$/progress`, which readiness depends on.
                window = WindowClientCapabilities().apply { workDoneProgress = true }
                workspace = WorkspaceClientCapabilities().apply { workspaceFolders = true }
                textDocument = TextDocumentClientCapabilities().apply {
                    // Without this, servers return a flat list and we lose class -> member nesting.
                    documentSymbol = DocumentSymbolCapabilities().apply { hierarchicalDocumentSymbolSupport = true }
                }
            }
        }
    }
}

/** kotlin-lsp's own notifications. Declared so lsp4j dispatches them instead of warning about each one on stderr. */
internal interface KotlinLspClient : LanguageClient {
    @JsonNotification("intellij/workspaceImportState")
    fun workspaceImportState(params: WorkspaceImportState)

    @JsonNotification("intellij/workspaceImportStatus")
    fun workspaceImportStatus(params: Any?)

    @JsonNotification("intellij/importLog")
    fun importLog(params: Any?)

    @JsonNotification("intellij/ready-for-test")
    fun readyForTest(params: Any?)
}

/** Payload of `intellij/workspaceImportState`, e.g. `{"phase": "FINISHED", "folders": [...]}`. */
internal class WorkspaceImportState {
    var phase: String? = null
}

/** Minimal client side of the protocol: feeds [readiness] and otherwise only needs to not break when the server talks to us. */
private class KronstadtLanguageClient(private val readiness: ReadinessTracker) : KotlinLspClient {
    override fun workspaceImportState(params: WorkspaceImportState) {
        params.phase?.let(readiness::importState)
    }

    override fun workspaceImportStatus(params: Any?) {}

    override fun importLog(params: Any?) {}

    override fun readyForTest(params: Any?) {}

    override fun createProgress(params: WorkDoneProgressCreateParams): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    override fun notifyProgress(params: ProgressParams) {
        val token = params.token.get().toString()
        when (val value = params.value.left) {
            is WorkDoneProgressBegin -> readiness.progressBegin(token, value.title)
            is WorkDoneProgressEnd -> readiness.progressEnd(token)
            else -> {}
        }
    }

    override fun logMessage(message: MessageParams) = System.err.println("[language-server] ${message.message}")

    override fun showMessage(messageParams: MessageParams) = System.err.println("[language-server] ${messageParams.message}")

    override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem?> =
        CompletableFuture.completedFuture(null)

    // Diagnostics will feed edit validation (kt_replace_symbol); ignored until then.
    override fun publishDiagnostics(diagnostics: PublishDiagnosticsParams) {}

    override fun telemetryEvent(`object`: Any?) {}

    override fun registerCapability(params: RegistrationParams): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    override fun unregisterCapability(params: UnregistrationParams): CompletableFuture<Void?> = CompletableFuture.completedFuture(null)

    override fun configuration(configurationParams: ConfigurationParams): CompletableFuture<List<Any?>> =
        CompletableFuture.completedFuture(configurationParams.items.map { null })
}
