package kronstadt.server

import kotlinx.coroutines.Deferred
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.CodeIntelligence
import java.nio.file.Path

/**
 * Lets the MCP server answer the client's handshake immediately while the language server is still booting
 * (that can take a while on a large Gradle project). Tool calls wait for startup, and fail with
 * the startup error if it failed.
 */
internal class DeferredCodeIntelligence(private val delegate: Deferred<Result<CodeIntelligence>>) : CodeIntelligence {
    override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> =
        delegate.await().getOrThrow().documentSymbols(file, document)

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> =
        delegate.await().getOrThrow().workspaceSymbols(query)
}
