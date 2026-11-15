package kronstadt.core.application

import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.CodeIntelligence
import java.nio.file.Path

/**
 * The files one Edit touches, as the code intelligence sees them (ADR 0003): the first analysis opens a file with the
 * text read from disk, later ones change it. It lasts one tool call: [use] closes every document it opened, however
 * the block ends.
 */
internal class ShadowWorkspace private constructor(private val intelligence: CodeIntelligence) {
    /** Analyses per file not yet closed: each analysis is one use of the document that needs its own close. */
    private val uses = mutableMapOf<Path, Int>()

    suspend fun symbols(file: Path, document: TextDocument): List<CodeSymbol> =
        counted(file) { intelligence.documentSymbols(file, document) }

    suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> =
        counted(file) { intelligence.diagnostics(file, document) }

    /** After Write-back: [document] is now [file]'s content on disk. */
    suspend fun saved(file: Path, document: TextDocument) = intelligence.saveDocument(file, document)

    private suspend fun <T> counted(file: Path, analysis: suspend () -> T): T {
        uses.merge(file, 1, Int::plus)
        return analysis()
    }

    private suspend fun closeAll() {
        uses.forEach { (file, count) -> repeat(count) { intelligence.closeDocument(file) } }
        uses.clear()
    }

    companion object {
        suspend fun <T> use(intelligence: CodeIntelligence, block: suspend (ShadowWorkspace) -> T): T {
            val workspace = ShadowWorkspace(intelligence)
            return try {
                block(workspace)
            } finally {
                workspace.closeAll()
            }
        }
    }
}
