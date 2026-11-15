package kronstadt.core.application

import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path

/** One file as read from the [FileStore], with its `documentSymbol` tree. */
internal class AnalysedFile(val file: Path, val document: TextDocument, val tree: SymbolTree) {
    companion object {
        /** Reads and analyses [file], closing it right away (ADR 0003); null if it doesn't exist. */
        suspend fun of(files: FileStore, intelligence: CodeIntelligence, file: Path): AnalysedFile? {
            val document = files.read(file) ?: return null
            val tree = try {
                SymbolTree(intelligence.documentSymbols(file, document))
            } finally {
                intelligence.closeDocument(file)
            }
            return AnalysedFile(file, document, tree)
        }

        fun isKotlin(file: Path): Boolean = file.fileName.toString().let { it.endsWith(".kt") || it.endsWith(".kts") }
    }
}
