package kronstadt.server

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
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
    override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> =
        delegate.await().getOrThrow().documentSymbols(file, document)

    /** Nothing can be open before startup finished, so there's nothing to close. */
    override suspend fun closeDocument(file: Path) {
        if (delegate.isCompleted) delegate.await().getOrNull()?.closeDocument(file)
    }

    override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> =
        delegate.await().getOrThrow().diagnostics(file, document)

    /** Like [closeDocument]: only a started language server can have the document open. */
    override suspend fun saveDocument(file: Path, document: TextDocument) {
        if (delegate.isCompleted) delegate.await().getOrNull()?.saveDocument(file, document)
    }

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> =
        delegate.await().getOrThrow().workspaceSymbols(query)

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
}
