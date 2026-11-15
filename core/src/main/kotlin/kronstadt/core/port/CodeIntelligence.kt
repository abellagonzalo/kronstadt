package kronstadt.core.port

import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SourceLocation
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

    /**
     * Diagnostics for exactly [document] as the content of [file], pulled on demand. Like [documentSymbols] it opens
     * the document, or changes its text, in the language server's view; each call is paired with a [closeDocument].
     */
    public suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic>

    /**
     * Tells the code intelligence that [document] is now [file]'s content on disk (Write-back). Callers still close
     * the document as usual; an implementation may keep it open past that close, for as long as the disk still holds
     * [document], if that is what makes project-wide search see the new text right away.
     */
    public suspend fun saveDocument(file: Path, document: TextDocument)

    /** Project-wide symbol search by (partial) name. Results outside the project, e.g. in dependencies, are excluded. */
    public suspend fun workspaceSymbols(query: String): List<SymbolLocation>

    /**
     * Every reference in project files to the Symbol whose name is at [position] in [document] (the content of [file]),
     * excluding its declaration. References outside the project, e.g. in dependencies, are excluded. Like
     * [documentSymbols] it opens the document, so each call is paired with a [closeDocument].
     */
    public suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation>

    /**
     * Waits up to [timeout] until the project index is complete. Readiness is live: it can be lost again later,
     * e.g. when the build is re-imported, so callers that need the index ask every time.
     */
    public suspend fun awaitReady(timeout: Duration): Readiness
}
