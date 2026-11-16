package kronstadt.lsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.future.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.LibrarySource
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.CodeIntelligence
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ConfigurationParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.DidSaveTextDocumentParams
import org.eclipse.lsp4j.DiagnosticCapabilities
import org.eclipse.lsp4j.DocumentDiagnosticParams
import org.eclipse.lsp4j.DocumentSymbolCapabilities
import org.eclipse.lsp4j.DocumentSymbolParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
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
import java.util.zip.ZipFile
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

    /**
     * [users] counts the calls that analysed this document and haven't closed it yet; read tools run concurrently.
     * A [retained] document stays open after its last close, for as long as calls find the disk unchanged since
     * Kronstadt wrote it (see [saveDocument]).
     */
    private data class OpenDocument(val version: Int, val text: String, val users: Int, val retained: Boolean = false)

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
            if (open.users > 1 || open.retained) {
                openDocuments[uri] = open.copy(users = maxOf(open.users - 1, 0))
            } else {
                openDocuments.remove(uri)
                server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri)))
            }
        }
    }

    /**
     * Pulls `textDocument/diagnostic` for [document]. Read tools may sync the same file meanwhile (they don't take the
     * write lock), so if its text changed while the request ran, the answer may describe other text: pull again.
     */
    override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> {
        val uri = LspMapping.uri(file)
        sync(uri, document)
        repeat(MAX_DIAGNOSTIC_PULLS) {
            val version = syncLock.withLock { openDocuments[uri]?.version }
            val report = request { server.textDocumentService.diagnostic(DocumentDiagnosticParams(TextDocumentIdentifier(uri))) }
            val unchanged = syncLock.withLock {
                val open = openDocuments[uri]
                open != null && open.version == version && open.text == document.text
            }
            if (unchanged) return LspMapping.diagnostics(report)
            syncAgain(uri, document)
        }
        throw IllegalStateException("${file.fileName} kept changing while its diagnostics were pulled")
    }

    /**
     * Sends `didSave` and retains the document: kotlin-lsp 271.2085.0 doesn't reread a file on `didSave`/`didClose`,
     * so closing it would leave `workspace/symbol` on the old text until its file watcher catches up (ADR 0003). Open,
     * the written text is what the index answers from. It stays open while calls read that same text from disk; the
     * first call that reads other text (an outside change) syncs it and its close hands the file back to the watcher.
     * A read tool may have synced the file meanwhile (it doesn't take the write lock), so [document] is put back first.
     */
    override suspend fun saveDocument(file: Path, document: TextDocument) {
        val uri = LspMapping.uri(file)
        syncAgain(uri, document)
        syncLock.withLock {
            val open = openDocuments[uri] ?: return@withLock
            server.textDocumentService.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri)))
            openDocuments[uri] = open.copy(retained = true)
        }
    }

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> {
        val result = request { server.workspaceService.symbol(WorkspaceSymbolParams(query)) }
        return LspMapping.workspaceSymbols(result)
    }

    override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> {
        val uri = LspMapping.uri(file)
        sync(uri, document)
        val params = ReferenceParams(
            TextDocumentIdentifier(uri),
            org.eclipse.lsp4j.Position(position.line, position.character),
            ReferenceContext(false),
        )
        return LspMapping.locations(request { server.textDocumentService.references(params) })
    }

    /**
     * kotlin-lsp 271.2085.0 points into a library's sources jar when Gradle has one (`jar:` URIs), and at class files
     * otherwise, including the JDK's (`jrt:` URIs). A sources-jar entry is read here, with its Symbols: kotlin-lsp
     * answers `documentSymbol` for a jar URI without it being opened. Other URIs are dropped.
     */
    override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> {
        val uri = LspMapping.uri(file)
        sync(uri, document)
        val params = DefinitionParams(TextDocumentIdentifier(uri), org.eclipse.lsp4j.Position(position.line, position.character))
        return LspMapping.definitionLocations(request { server.textDocumentService.definition(params) }).mapNotNull { (target, range) ->
            when (val parsed = LspMapping.definitionTarget(target)) {
                is DefinitionTarget.Local -> Definition.InSource(SourceLocation(parsed.file, range))
                is DefinitionTarget.Jar -> Definition.InLibrary(parsed.jar.fileName.toString(), parsed.entry, range, librarySource(target, parsed))
                is DefinitionTarget.Jdk -> Definition.InLibrary("JDK ${parsed.module}", parsed.entry, range, source = null)
                null -> null
            }
        }
    }

    /** The entry's text and Symbols, if it is source text that can be read; null for a class file. */
    private suspend fun librarySource(uri: String, target: DefinitionTarget.Jar): LibrarySource? {
        if (target.entry.endsWith(".class")) return null
        val text = withContext(Dispatchers.IO) {
            runCatching {
                ZipFile(target.jar.toFile()).use { zip ->
                    zip.getEntry(target.entry)?.let { entry -> zip.getInputStream(entry).use { String(it.readAllBytes(), Charsets.UTF_8) } }
                }
            }.getOrNull()
        } ?: return null
        val symbols = request { server.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))) }
        return LibrarySource(TextDocument(text), LspMapping.documentSymbols(symbols))
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

    /** Puts [document]'s text back without counting another user: the caller already holds the document open. */
    private suspend fun syncAgain(uri: String, document: TextDocument) = syncLock.withLock {
        val open = openDocuments[uri] ?: return@withLock
        if (open.text == document.text) return@withLock
        val version = open.version + 1
        server.textDocumentService.didChange(
            DidChangeTextDocumentParams(
                VersionedTextDocumentIdentifier(uri, version),
                listOf(TextDocumentContentChangeEvent(document.text)),
            ),
        )
        openDocuments[uri] = open.copy(version = version, text = document.text)
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
        private const val MAX_DIAGNOSTIC_PULLS = 3

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
                    // Validation pulls diagnostics for exactly the text it sent, instead of waiting for a push.
                    diagnostic = DiagnosticCapabilities()
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
