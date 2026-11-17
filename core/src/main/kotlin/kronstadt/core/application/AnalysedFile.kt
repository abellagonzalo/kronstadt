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
