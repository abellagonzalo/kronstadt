package kronstadt.core.application

import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path

class FakeFileStore(private val root: Path, private val files: Map<String, String>) : FileStore {
    override fun resolve(path: String): Path? = root.resolve(path).normalize().takeIf { it.startsWith(root) }
    override suspend fun read(file: Path): TextDocument? = files[root.relativize(file).toString()]?.let(::TextDocument)
    override fun display(file: Path): String = root.relativize(file).toString()
}

class FakeCodeIntelligence(
    private val documentSymbols: List<CodeSymbol> = emptyList(),
    private val workspaceSymbols: List<SymbolLocation> = emptyList(),
) : CodeIntelligence {
    val analysed = mutableListOf<Pair<Path, TextDocument>>()

    override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> {
        analysed += file to document
        return documentSymbols
    }

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> = workspaceSymbols
}
