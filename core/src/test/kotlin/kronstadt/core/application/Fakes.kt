package kronstadt.core.application

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

class FakeFileStore(private val root: Path, private val files: Map<String, String>) : FileStore {
    override fun resolve(path: String): Path? = root.resolve(path).normalize().takeIf { it.startsWith(root) }
    override suspend fun read(file: Path): TextDocument? = files[root.relativize(file).toString()]?.let(::TextDocument)
    override fun display(file: Path): String = root.relativize(file).toString()
}

/** Ready unless [notReadyPhase] is given; then it stays in that phase until [becomeReady]. */
class FakeCodeIntelligence(
    private val documentSymbols: List<CodeSymbol> = emptyList(),
    private val workspaceSymbols: List<SymbolLocation> = emptyList(),
    notReadyPhase: String? = null,
) : CodeIntelligence {
    val analysed = mutableListOf<Pair<Path, TextDocument>>()
    val searched = mutableListOf<String>()

    /** Documents analysed and not closed since. */
    val openDocuments = mutableSetOf<Path>()
    private val phase = MutableStateFlow(notReadyPhase)

    fun becomeReady() {
        phase.value = null
    }

    override suspend fun awaitReady(timeout: Duration): Readiness {
        withTimeoutOrNull(timeout) { phase.first { it == null } }
        return phase.value?.let { Readiness.NotReady(it, elapsed = timeout) } ?: Readiness.Ready
    }

    override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> {
        analysed += file to document
        openDocuments.add(file)
        return documentSymbols
    }

    override suspend fun closeDocument(file: Path) {
        openDocuments.remove(file)
    }

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> {
        searched += query
        return workspaceSymbols
    }
}
