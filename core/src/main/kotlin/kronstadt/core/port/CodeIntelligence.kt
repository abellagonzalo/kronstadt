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

    /** Project-wide symbol search by (partial) name. Results outside the project, e.g. in dependencies, are excluded. */
    public suspend fun workspaceSymbols(query: String): List<SymbolLocation>

    /**
     * Waits up to [timeout] until the project index is complete. Readiness is live: it can be lost again later,
     * e.g. when the build is re-imported, so callers that need the index ask every time.
     */
    public suspend fun awaitReady(timeout: Duration): Readiness
}
