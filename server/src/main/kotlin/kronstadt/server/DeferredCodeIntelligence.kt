package kronstadt.server

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
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
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Lets the MCP server answer the client's handshake immediately while the language server is still booting
 * (that can take a while on a large Gradle project). Tool calls wait for startup, and fail with
 * the startup error if it failed.
 */
internal class DeferredCodeIntelligence(private val delegate: Deferred<Result<CodeIntelligence>>) : CodeIntelligence {
    /**
     * A session only waits for startup at its first analysis, so a call that analyses nothing (e.g. its file doesn't
     * exist) answers at once, even if startup is still running or failed. The language server's own session is
     * opened then, and held until this one ends.
     */
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T {
        if (delegate.isCompleted) delegate.await().getOrNull()?.let { return it.session(block) }
        return coroutineScope {
            val session = StartingSession(this)
            try {
                block(session)
            } finally {
                session.end()
            }
        }
    }

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> = started().workspaceSymbols(query)

    /** Startup counts against [timeout]: the agent waits once, however the time is split. */
    override suspend fun awaitReady(timeout: Duration): Readiness {
        val start = TimeSource.Monotonic.markNow()
        val started = withTimeoutOrNull(timeout) { delegate.await() }
            ?: return Readiness.NotReady("Starting", start.elapsedNow())
        return when (val readiness = started.getOrThrow().awaitReady(timeout - start.elapsedNow())) {
            Readiness.Ready -> Readiness.Ready
            is Readiness.NotReady -> readiness.copy(elapsed = start.elapsedNow())
        }
    }

    private suspend fun started(): CodeIntelligence = delegate.await().getOrThrow()

    /**
     * A session opened before startup finished. Its first analysis waits for startup, then opens the started language
     * server's session in a child of [scope], which holds it open until [end].
     */
    private inner class StartingSession(private val scope: CoroutineScope) : AnalysisSession {
        private val opening = Mutex()
        private var opened: AnalysisSession? = null
        private val ended = CompletableDeferred<Unit>()

        private suspend fun open(): AnalysisSession = opening.withLock {
            opened ?: run {
                val intelligence = started()
                val session = CompletableDeferred<AnalysisSession>()
                scope.launch { intelligence.session { session.complete(it); ended.await() } }
                session.await().also { opened = it }
            }
        }

        fun end() {
            ended.complete(Unit)
        }

        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> =
            open().documentSymbols(file, document)

        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> =
            open().diagnostics(file, document)

        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> =
            open().references(file, document, position)

        override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> =
            open().definition(file, document, position)

        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation =
            open().prepareRename(file, document, position)

        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename =
            open().rename(file, document, position, newName)

        override suspend fun organizeImports(file: Path, document: TextDocument): TextDocument? =
            open().organizeImports(file, document)

        /** Only an analysis opens documents, so with nothing analysed yet there is nothing to tell. */
        override suspend fun writtenBack(file: Path, document: TextDocument) {
            opening.withLock { opened }?.writtenBack(file, document)
        }
    }
}
