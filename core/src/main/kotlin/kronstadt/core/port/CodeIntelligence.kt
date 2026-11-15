package kronstadt.core.port

import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Semantic knowledge about Kotlin code. Implemented today by a language server (see the `lsp` module);
 * could equally be backed by the Kotlin compiler's PSI/Analysis API, or a fake in tests.
 */
public interface CodeIntelligence {
    /**
     * Symbols declared in [file], as a tree. The caller passes the [document] content it believes is current,
     * so implementations analyse exactly that text (the seed of the shadow workspace) not whatever is on disk.
     */
    public suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol>

    /**
     * Hands [file] back to the language server's view of the disk (ADR 0003): every tool closes each document it
     * analysed before it returns, so no document state survives a tool call. Closing a file that isn't open is a no-op.
     */
    public suspend fun closeDocument(file: Path)

    /** Project-wide symbol search by (partial) name. Results outside the project, e.g. in dependencies, are excluded. */
    public suspend fun workspaceSymbols(query: String): List<SymbolLocation>

    /**
     * Waits up to [timeout] until the project index is complete. Readiness is live: it can be lost again later,
     * e.g. when the build is re-imported, so callers that need the index ask every time.
     */
    public suspend fun awaitReady(timeout: Duration): Readiness
}
