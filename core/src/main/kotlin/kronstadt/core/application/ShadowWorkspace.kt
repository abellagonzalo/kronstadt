package kronstadt.core.application

import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.AnalysisSession
import java.nio.file.Path

/**
 * The files one Edit touches, as the code intelligence sees them (ADR 0003): the first analysis opens a file with the
 * text read from disk, later ones change it. It lives in the tool call's [AnalysisSession], which closes every
 * document it opened when the call ends.
 */
internal class ShadowWorkspace(private val session: AnalysisSession) {
    suspend fun symbols(file: Path, document: TextDocument): List<CodeSymbol> = session.documentSymbols(file, document)

    suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> = session.diagnostics(file, document)

    /** After Write-back: [document] is now [file]'s content on disk. */
    suspend fun writtenBack(file: Path, document: TextDocument) = session.writtenBack(file, document)
}
