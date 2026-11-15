package kronstadt.core.application

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.ContentHash
import kronstadt.core.port.FileStore
import kronstadt.core.port.HashedDocument
import kronstadt.core.port.WriteResult
import java.nio.file.Path
import kotlin.time.Duration

/** Files in memory, keyed by project-relative path. A hash is the text itself, so any change changes it. */
class FakeFileStore(private val root: Path, files: Map<String, String>) : FileStore {
    private val files = files.toMutableMap()

    /** Every successful Write-back, in order: project-relative path to the text written. */
    val written = mutableListOf<Pair<String, String>>()

    /** The current content of [path], as an outside writer (or a test) sees it. */
    fun content(path: String): String? = files[path]

    /** A write that doesn't go through Kronstadt, e.g. the user's IDE. */
    fun writeOutside(path: String, text: String) {
        files[path] = text
    }

    override fun resolve(path: String): Path? = root.resolve(path).normalize().takeIf { it.startsWith(root) }
    override suspend fun read(file: Path): TextDocument? = files[key(file)]?.let(::TextDocument)
    override suspend fun readHashed(file: Path): HashedDocument? =
        files[key(file)]?.let { HashedDocument(TextDocument(it), ContentHash(it)) }

    override suspend fun writeIfUnchanged(file: Path, basedOn: ContentHash, document: TextDocument): WriteResult {
        if (files[key(file)] != basedOn.value) return WriteResult.ChangedOnDisk
        files[key(file)] = document.text
        written += key(file) to document.text
        return WriteResult.Written
    }

    override fun display(file: Path): String = key(file)

    private fun key(file: Path) = root.relativize(file).toString()
}

/**
 * Ready unless [notReadyPhase] is given; then it stays in that phase until [becomeReady].
 * Every file has [documentSymbols], unless [symbolsByText] gives its exact text, or [symbolsByFile] the file, its own.
 * Workspace search returns [workspaceSymbols] whatever the query, unless an [index] is given: then it returns the
 * index entries whose name contains the query, ignoring case: kotlin-lsp 271.2085.0 matches substrings, not typos.
 * Diagnostics are [diagnosticsByText] for the exact text, else none; each pull takes [diagnosticsDelay] and first runs
 * [beforeDiagnostics].
 *
 * Documents are modelled like the language server sees them: the first analysis opens a document at version 1, an
 * analysis of different text changes it to the next version, and each analysis must be paired with a close.
 */
class FakeCodeIntelligence(
    private val documentSymbols: List<CodeSymbol> = emptyList(),
    private val workspaceSymbols: List<SymbolLocation> = emptyList(),
    notReadyPhase: String? = null,
    private val symbolsByFile: Map<Path, List<CodeSymbol>> = emptyMap(),
    private val index: List<SymbolLocation>? = null,
    private val symbolsByText: Map<String, List<CodeSymbol>> = emptyMap(),
    private val diagnosticsByText: Map<String, List<Diagnostic>> = emptyMap(),
    private val diagnosticsDelay: Duration = Duration.ZERO,
    private val beforeDiagnostics: suspend () -> Unit = {},
) : CodeIntelligence {
    val analysed = mutableListOf<Pair<Path, TextDocument>>()
    val searched = mutableListOf<String>()

    /** What the language server was told, in order: `open <file> v1`, `change <file> v2`, `save <file>`, `close <file>`. */
    val events = mutableListOf<String>()

    private data class Open(val version: Int, val text: String, val users: Int)

    private val open = mutableMapOf<Path, Open>()

    /** Documents analysed and not closed since. */
    val openDocuments: Set<Path> get() = open.keys

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
        sync(file, document)
        return symbolsByText[document.text] ?: symbolsByFile[file] ?: documentSymbols
    }

    override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> {
        sync(file, document)
        beforeDiagnostics()
        delay(diagnosticsDelay)
        return diagnosticsByText[document.text].orEmpty()
    }

    override suspend fun saveDocument(file: Path, document: TextDocument) {
        if (file in open) events += "save ${file.fileName}"
    }

    override suspend fun closeDocument(file: Path) {
        val current = open[file] ?: return
        if (current.users > 1) {
            open[file] = current.copy(users = current.users - 1)
        } else {
            open.remove(file)
            events += "close ${file.fileName}"
        }
    }

    override suspend fun workspaceSymbols(query: String): List<SymbolLocation> {
        searched += query
        return index?.filter { it.name.contains(query, ignoreCase = true) } ?: workspaceSymbols
    }

    private fun sync(file: Path, document: TextDocument) {
        val current = open[file]
        open[file] = when {
            current == null -> Open(1, document.text, 1).also { events += "open ${file.fileName} v1" }
            current.text != document.text -> Open(current.version + 1, document.text, current.users + 1)
                .also { events += "change ${file.fileName} v${it.version}" }
            else -> current.copy(users = current.users + 1)
        }
    }
}
