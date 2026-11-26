package kronstadt.server

import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.ImportDirective
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import java.nio.file.Path
import kotlin.time.Duration

/** An [AnalysisSession] whose every method fails, naming itself; a test's fake overrides only what the test reaches. */
internal open class UnstubbedAnalysisSession : AnalysisSession {
    override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> = notStubbed("documentSymbols")
    override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> = notStubbed("diagnostics")
    override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> =
        notStubbed("references")
    override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> =
        notStubbed("definition")
    override suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation =
        notStubbed("prepareRename")
    override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename =
        notStubbed("rename")
    override suspend fun unusedImports(file: Path, document: TextDocument, among: Set<ImportDirective>, budget: Duration): Set<ImportDirective> =
        notStubbed("unusedImports")
    override suspend fun writtenBack(file: Path, document: TextDocument): Unit = notStubbed("writtenBack")
}

/**
 * A [CodeIntelligence] whose every method fails, naming itself, except [session], which hands out an
 * [UnstubbedAnalysisSession]; a test's fake overrides only what the test reaches.
 */
internal open class UnstubbedCodeIntelligence : CodeIntelligence {
    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T = block(UnstubbedAnalysisSession())
    override suspend fun workspaceSymbols(query: String): SymbolSearch = notStubbed("workspaceSymbols")
    override suspend fun awaitReady(timeout: Duration): Readiness = notStubbed("awaitReady")
}

private fun notStubbed(method: String): Nothing = error("not stubbed: $method")
