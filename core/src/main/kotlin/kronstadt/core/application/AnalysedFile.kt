package kronstadt.core.application

import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.FileStore
import java.nio.file.Path

/** One file as read from the [FileStore], with its `documentSymbol` tree. */
internal class AnalysedFile(val file: Path, val document: TextDocument, val tree: SymbolTree) {
    companion object {
        /** Reads and analyses [file] in [session]; null if it doesn't exist. */
        suspend fun of(files: FileStore, session: AnalysisSession, file: Path): AnalysedFile? {
            val document = files.read(file) ?: return null
            return AnalysedFile(file, document, SymbolTree(session.documentSymbols(file, document)))
        }

        fun isKotlin(file: Path): Boolean = file.fileName.toString().let { it.endsWith(".kt") || it.endsWith(".kts") }
    }
}

/**
 * Where in-file resolution reads a file's text and `documentSymbol` tree: the file on disk for read tools, the Shadow
 * Workspace's current text for `kt_edit`.
 */
internal fun interface DocumentSource {
    /** [file] as this source has it, analysed; null if it doesn't exist. */
    suspend fun analysed(file: Path): AnalysedFile?

    companion object {
        /** The file as read from [files], analysed in [session]. */
        fun disk(files: FileStore, session: AnalysisSession) = DocumentSource { AnalysedFile.of(files, session, it) }
    }
}
