package kronstadt.lsp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.SharedFlow
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
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
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
import org.eclipse.lsp4j.ExecuteCommandParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.ProgressParams
import org.eclipse.lsp4j.ReferenceContext
import org.eclipse.lsp4j.ReferenceParams
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.RegistrationParams
import org.eclipse.lsp4j.RenameCapabilities
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.ResourceOperationKind
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
import org.eclipse.lsp4j.WorkspaceEditCapabilities
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.WorkspaceSymbolParams
import org.eclipse.lsp4j.jsonrpc.ResponseErrorException
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
    private val refusedEdits: RefusedEdits,
) : CodeIntelligence, AutoCloseable {

    /**
     * [holders] counts the sessions that analysed this document and haven't ended yet; read tools run sessions
     * concurrently. A [retained] document stays open after its last holder ended, for as long as sessions find the
     * disk unchanged since Kronstadt wrote it (see [Session.writtenBack]).
     */
    private data class OpenDocument(val version: Int, val text: String, val holders: Int, val retained: Boolean = false)

    private val syncLock = Mutex()
    private val openDocuments = mutableMapOf<String, OpenDocument>()

    /** Ending the session closes its documents even if the caller was cancelled: a leaked document goes stale (ADR 0003). */
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T {
        val session = Session()
        try {
            return block(session)
        } finally {
            withContext(NonCancellable) { session.end() }
        }
    }

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> {
        val result = request { server.workspaceService.symbol(WorkspaceSymbolParams(query)) }
        return LspMapping.workspaceSymbols(result)
    }

    override suspend fun awaitReady(timeout: Duration): Readiness = readiness.awaitReady(timeout)

    /** The readiness signals kotlin-lsp sends from here on; see [ReadinessTracker.signals]. */
    internal val readinessSignals: SharedFlow<ReadinessSignal> get() = readiness.signals

    /** The documents one tool call holds open: its first analysis of a file counts it as one more holder. */
    private inner class Session : AnalysisSession {
        /** Guarded by [syncLock], like [openDocuments]. */
        private val held = mutableSetOf<String>()
        private var ended = false

        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> {
            val uri = LspMapping.uri(file)
            sync(uri, document)
            val result = request { server.textDocumentService.documentSymbol(DocumentSymbolParams(TextDocumentIdentifier(uri))) }
            return LspMapping.documentSymbols(result)
        }

        /** Pulls `textDocument/diagnostic` for [document]. */
        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> {
            val params = DocumentDiagnosticParams(TextDocumentIdentifier(LspMapping.uri(file)))
            return LspMapping.diagnostics(answerFor(file, document, "diagnostics were pulled") { request { server.textDocumentService.diagnostic(params) } })
        }

        /**
         * Runs kotlin-lsp's organize-imports command on [document] and applies the edit it sends back, which [refusedEdits]
         * refused, to a copy: kotlin-lsp 271.2085.0 reports no unused import as a diagnostic, and its "Organize imports" code
         * action carries no edit, only this command, which sends the edit before it answers. An error answer is no answer.
         */
        override suspend fun organizeImports(file: Path, document: TextDocument): TextDocument? {
            val uri = LspMapping.uri(file)
            val params = ExecuteCommandParams(ORGANIZE_IMPORTS, listOf<Any>(uri))
            // Being unsure counts as used (ADR 0004): any failure, not only an error answer, is no answer.
            val edits = try {
                answerFor(file, document, "imports were organized") {
                    refusedEdits.during { request { server.workspaceService.executeCommand(params) } }.second
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } ?: return null
            return LspMapping.organized(document, uri, edits)
        }

        /**
         * [ask]'s answer about [document] as [file]'s content. Other sessions may sync the same file meanwhile (read tools
         * don't take the write lock), so if its text changed while [ask] ran, the answer may describe other text: ask again.
         * [what] ends the failure message, e.g. "diagnostics were pulled".
         */
        private suspend fun <T> answerFor(file: Path, document: TextDocument, what: String, ask: suspend () -> T): T {
            val uri = LspMapping.uri(file)
            sync(uri, document)
            repeat(MAX_ASKS) {
                val version = syncLock.withLock { openDocuments[uri]?.version }
                val answer = ask()
                val unchanged = syncLock.withLock {
                    val open = openDocuments[uri]
                    open != null && open.version == version && open.text == document.text
                }
                if (unchanged) return answer
                syncAgain(uri, document)
            }
            throw IllegalStateException("${file.fileName} kept changing while its $what")
        }

        /**
         * Sends `didSave` and retains the document: kotlin-lsp 271.2085.0 doesn't reread a file on `didSave`/`didClose`,
         * so closing it would leave `workspace/symbol` on the old text until its file watcher catches up (ADR 0003).
         * Open, the written text is what the index answers from. It stays open while sessions read that same text from
         * disk; the first session that reads other text (an outside change) syncs it, and closes it when it ends,
         * handing the file back to the watcher. Another session may have synced the file meanwhile (read tools don't
         * take the write lock), so [document] is put back first.
         */
        override suspend fun writtenBack(file: Path, document: TextDocument) {
            val uri = LspMapping.uri(file)
            syncAgain(uri, document)
            syncLock.withLock {
                val open = openDocuments[uri] ?: return@withLock
                server.textDocumentService.didSave(DidSaveTextDocumentParams(TextDocumentIdentifier(uri)))
                openDocuments[uri] = open.copy(retained = true)
            }
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

        /** kotlin-lsp 271.2085.0 refuses with an error response, e.g. "This element cannot be renamed", rather than null. */
        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation {
            val uri = LspMapping.uri(file)
            sync(uri, document)
            val params = PrepareRenameParams(TextDocumentIdentifier(uri), org.eclipse.lsp4j.Position(position.line, position.character))
            return try {
                LspMapping.prepareRename(request { server.textDocumentService.prepareRename(params) }, position)
            } catch (e: ResponseErrorException) {
                RenamePreparation.Refused(e.responseError.message)
            }
        }

        /**
         * kotlin-lsp 271.2085.0 answers with `documentChanges`: one minimal text edit per reference, in files it never opened
         * too, and a file rename after the edits when a class is named like its file; it refuses with an error response.
         */
        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename {
            val uri = LspMapping.uri(file)
            sync(uri, document)
            val params = RenameParams(TextDocumentIdentifier(uri), org.eclipse.lsp4j.Position(position.line, position.character), newName)
            return try {
                LspMapping.rename(request { server.textDocumentService.rename(params) })
            } catch (e: ResponseErrorException) {
                Rename.Refused(e.responseError.message)
            }
        }

        /**
         * Gives up this session's hold on each document; one no session holds, and not retained, is closed. The table is
         * settled first and every close is attempted, so a failing `didClose` (the server is gone) leaves no bookkeeping
         * behind; the first failure is rethrown after the rest were tried.
         */
        suspend fun end() = syncLock.withLock {
            ended = true
            val closing = held.filter { uri ->
                val open = openDocuments[uri] ?: return@filter false
                if (open.holders > 1 || open.retained) {
                    openDocuments[uri] = open.copy(holders = maxOf(open.holders - 1, 0))
                    false
                } else {
                    openDocuments.remove(uri)
                    true
                }
            }
            held.clear()
            val failures = closing.mapNotNull { uri ->
                runCatching { server.textDocumentService.didClose(DidCloseTextDocumentParams(TextDocumentIdentifier(uri))) }.exceptionOrNull()
            }
            failures.firstOrNull()?.let { throw it }
        }

        /** Counts this session as a holder only once the notification went out, so a failed one leaves no hold to give up. */
        private suspend fun sync(uri: String, document: TextDocument) = syncLock.withLock {
            check(!ended) { "analysis session used after it ended" }
            val open = openDocuments[uri]
            val holders = (open?.holders ?: 0) + if (uri in held) 0 else 1
            when {
                open == null -> {
                    server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "kotlin", 1, document.text)))
                    openDocuments[uri] = OpenDocument(1, document.text, holders)
                }
                open.text != document.text -> {
                    val version = open.version + 1
                    server.textDocumentService.didChange(
                        DidChangeTextDocumentParams(
                            VersionedTextDocumentIdentifier(uri, version),
                            listOf(TextDocumentContentChangeEvent(document.text)),
                        ),
                    )
                    openDocuments[uri] = OpenDocument(version, document.text, holders)
                }
                else -> openDocuments[uri] = open.copy(holders = holders)
            }
            held += uri
        }

        /** Puts [document]'s text back without counting another holder: this session already holds the document. */
        private suspend fun syncAgain(uri: String, document: TextDocument) = syncLock.withLock {
            check(!ended) { "analysis session used after it ended" }
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

    private suspend fun <T> request(call: () -> CompletableFuture<T>): T = awaitAlive(process, requestTimeout, call())

    override fun close() {
        shutdown()
    }

    /**
     * Asks kotlin-lsp to shut down and exit, and waits for its process to end; kills it if it doesn't in time.
     * Returns true only for a clean exit: `shutdown` answered and the process exited by itself with code 0, so its
     * index was closed properly and may become the Index Seed.
     */
    public fun shutdown(): Boolean {
        val answered = requestExit(SHUTDOWN_ANSWER_WAIT)
        val exited = process.waitFor(EXIT_TIMEOUT.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        if (!exited) process.destroyForcibly()
        return answered && exited && process.exitValue() == 0
    }

    /**
     * Asks kotlin-lsp to shut down, waiting up to [answerWait] for its answer, then to exit, without waiting for it to.
     * Returns whether `shutdown` was answered: kotlin-lsp then closes its index and exits by itself.
     */
    public fun requestExit(answerWait: Duration): Boolean {
        val answered = runCatching { server.shutdown().get(answerWait.inWholeMilliseconds, TimeUnit.MILLISECONDS) }.isSuccess
        runCatching { server.exit() }
        return answered
    }

    /** kotlin-lsp's process, for whoever waits for it to exit after [requestExit]. */
    public val processHandle: ProcessHandle get() = process.toHandle()

    public companion object {
        /** kotlin-lsp's command behind its "Organize imports" code action; it takes the file's URI. */
        private const val ORGANIZE_IMPORTS = "kotlin.organize.imports"

        /** How often [Session.answerFor] asks before giving up on a file other sessions keep changing. */
        private const val MAX_ASKS = 3

        /** How long [shutdown] waits for kotlin-lsp to answer `shutdown`. */
        private val SHUTDOWN_ANSWER_WAIT = 5.seconds

        /** How long [shutdown] waits for kotlin-lsp to close its index and exit. */
        private val EXIT_TIMEOUT = 10.seconds

        /**
         * Spawns [command] (e.g. `kotlin-lsp --stdio`) in [projectRoot] and completes the LSP handshake.
         * kotlin-lsp keeps its index in [indexDir] (created if missing), never in JetBrains' default cache.
         * The server's stderr is inherited, which is safe: MCP only uses our stdout.
         * Returns as soon as `initialize` answers; the import and indexing go on in the background (see [awaitReady]).
         */
        public suspend fun start(
            command: List<String>,
            projectRoot: Path,
            indexDir: Path,
            requestTimeout: Duration = 120.seconds,
        ): LspCodeIntelligence {
            val process = ProcessBuilder(command)
                .directory(projectRoot.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
            val readiness = ReadinessTracker()
            val refusedEdits = RefusedEdits()
            val launcher = LSPLauncher.createClientLauncher(
                KronstadtLanguageClient(readiness, refusedEdits),
                process.inputStream,
                process.outputStream,
            )
            launcher.startListening()
            val server = launcher.remoteProxy

            val serverVersion = try {
                awaitAlive(process, requestTimeout, server.initialize(initializeParams(projectRoot, indexDir))).serverInfo?.version
            } catch (e: CancellationException) {
                process.destroyForcibly()
                throw e
            } catch (e: Exception) {
                process.destroyForcibly()
                throw IllegalStateException("Language server '${command.joinToString(" ")}' failed to initialize: ${e.message}", e)
            }
            KotlinLspVersion.mismatchWarning(serverVersion)?.let(System.err::println)
            server.initialized(InitializedParams())
            return LspCodeIntelligence(process, server, requestTimeout, readiness, serverVersion, refusedEdits)
        }

        /** An adapter over an already initialized [server] running as [process]; for tests with a stubbed server. */
        internal fun connected(process: Process, server: LanguageServer, requestTimeout: Duration): LspCodeIntelligence =
            LspCodeIntelligence(process, server, requestTimeout, ReadinessTracker(), serverVersion = null, RefusedEdits())

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

        private fun initializeParams(projectRoot: Path, indexDir: Path) = InitializeParams().apply {
            processId = ProcessHandle.current().pid().toInt()
            clientInfo = org.eclipse.lsp4j.ClientInfo("kronstadt")
            // kotlin-lsp uses this directory verbatim in place of its default `.../index/kotlin-server`.
            initializationOptions = mapOf("indexDir" to indexDir.toString())
            workspaceFolders = listOf(WorkspaceFolder(LspMapping.uri(projectRoot), projectRoot.fileName.toString()))
            capabilities = ClientCapabilities().apply {
                // Makes kotlin-lsp report its import and indexing as `$/progress`, which readiness depends on.
                window = WindowClientCapabilities().apply { workDoneProgress = true }
                workspace = WorkspaceClientCapabilities().apply {
                    workspaceFolders = true
                    // Rename answers in document changes, with a file rename when a class is named like its file.
                    workspaceEdit = WorkspaceEditCapabilities().apply {
                        documentChanges = true
                        resourceOperations = listOf(ResourceOperationKind.Rename)
                    }
                }
                textDocument = TextDocumentClientCapabilities().apply {
                    // Without this, servers return a flat list and we lose class -> member nesting.
                    documentSymbol = DocumentSymbolCapabilities().apply { hierarchicalDocumentSymbolSupport = true }
                    // Validation pulls diagnostics for exactly the text it sent, instead of waiting for a push.
                    diagnostic = DiagnosticCapabilities()
                    rename = RenameCapabilities().apply { prepareSupport = true }
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

/**
 * Minimal client side of the protocol: feeds [readiness], hands edits the server asks to apply to [refusedEdits], and
 * otherwise only needs to not break when the server talks to us.
 */
private class KronstadtLanguageClient(
    private val readiness: ReadinessTracker,
    private val refusedEdits: RefusedEdits,
) : KotlinLspClient {
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

    /** Refused, like every edit the server asks for; see [RefusedEdits]. */
    override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> =
        CompletableFuture.completedFuture(refusedEdits.refuse(params))
}
