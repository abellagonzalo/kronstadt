package kronstadt.core.port

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
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Semantic knowledge about Kotlin code. Implemented today by a language server (see the `lsp` module);
 * could equally be backed by the Kotlin compiler's PSI/Analysis API, or a fake in tests.
 *
 * Analysing a document opens it in the code intelligence's view, which then stops tracking that file on disk. So
 * documents are only analysed inside a [session], and every document a session opened is closed when it ends
 * (ADR 0003): no document state survives a tool call. A tool call opens at most one session.
 */
public interface CodeIntelligence {
    /**
     * Runs [block] with an [AnalysisSession]. However [block] ends (normally, by exception or by cancellation), every
     * document the session opened is closed, unless another session still holds it. Sessions may run concurrently
     * and open the same file. The session must not be used after [block] returns.
     */
    public suspend fun <T> session(block: suspend (AnalysisSession) -> T): T

    /** Project-wide symbol search by (partial) name. Results outside the project, e.g. in dependencies, are excluded. */
    public suspend fun workspaceSymbols(query: String): List<SymbolLocation>

    /**
     * Waits up to [timeout] until the project index is complete. Readiness is live: it can be lost again later,
     * e.g. when the build is re-imported, so callers that need the index ask every time.
     */
    public suspend fun awaitReady(timeout: Duration): Readiness
}

/**
 * The documents one tool call analyses, open in the code intelligence's view until its [CodeIntelligence.session]
 * ends. Each operation takes the [TextDocument] the caller believes is [file]'s content, so the analysis is of exactly
 * that text (e.g. the Shadow Workspace's), not whatever is on disk. The first operation on a file opens it with that
 * text; a later one with different text changes it.
 */
public interface AnalysisSession {
    /** Symbols declared in [document], the content of [file], as a tree. */
    public suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol>

    /** Diagnostics for exactly [document] as the content of [file], pulled on demand. */
    public suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic>

    /**
     * Every reference in project files to the Symbol whose name is at [position] in [document] (the content of [file]),
     * excluding its declaration. References outside the project, e.g. in dependencies, are excluded.
     */
    public suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation>

    /**
     * Where the name at [position] in [document] (the content of [file]) is declared, in the language server's
     * order; several for e.g. `expect`/`actual`, none for a keyword, a literal or an unresolved name.
     */
    public suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition>

    /**
     * Whether the name at [position] in [document] (the content of [file]) can be renamed; refused e.g. for a keyword
     * or a library declaration.
     */
    public suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation

    /**
     * What renaming the Symbol whose name is at [position] in [document] (the content of [file]) to [newName] changes,
     * across files, including ones never opened, and any file rename; nothing is applied.
     */
    public suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename

    /**
     * [document] (the content of [file]) as it would read once the code intelligence organized its imports, or null if it
     * gives no answer; nothing is applied. Only which imports it leaves is meaningful: it drops those it finds unused, and
     * the order and layout of the rest are its own.
     */
    public suspend fun organizeImports(file: Path, document: TextDocument): TextDocument?

    /** Write-back has happened: [document] is now [file]'s content on disk. */
    public suspend fun writtenBack(file: Path, document: TextDocument)
}
