package kronstadt.core.application

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextRange
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.ContentHash
import kronstadt.core.port.FileStore
import kronstadt.core.port.HashedDocument
import kronstadt.core.port.WriteResult
import java.io.IOException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Files in memory, keyed by project-relative path. A hash is the text itself, so any change changes it. A write to one
 * of [failingWrites] fails with an [IOException], as a full disk would.
 */
class FakeFileStore(
    private val root: Path,
    files: Map<String, String>,
    private val failingWrites: Set<String> = emptySet(),
) : FileStore {
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
        if (key(file) in failingWrites) throw IOException("No space left on device")
        files[key(file)] = document.text
        written += key(file) to document.text
        return WriteResult.Written
    }

    override suspend fun moveIfUnchanged(file: Path, basedOn: ContentHash, target: Path, document: TextDocument): WriteResult {
        if (files[key(file)] != basedOn.value) return WriteResult.ChangedOnDisk
        if (key(target) in files) return WriteResult.TargetExists
        if (key(target) in failingWrites) throw IOException("No space left on device")
        files.remove(key(file))
        files[key(target)] = document.text
        written += key(target) to document.text
        return WriteResult.Written
    }

    override suspend fun writeIfAbsent(file: Path, document: TextDocument): WriteResult {
        if (key(file) in files) return WriteResult.ChangedOnDisk
        if (key(file) in failingWrites) throw IOException("No space left on device")
        files[key(file)] = document.text
        written += key(file) to document.text
        return WriteResult.Written
    }

    override fun display(file: Path): String = key(file)

    override suspend fun kotlinFiles(): List<Path> =
        files.keys.filter { it.endsWith(".kt") || it.endsWith(".kts") }.sorted().map { root.resolve(it) }

    private fun key(file: Path) = root.relativize(file).toString()
}

/** The [LookupFailure] a tool's result reports; fails unless it reports one. */
fun ReadSymbolResult.lookupFailure(): LookupFailure = assertIs<ReadSymbolResult.LookupFailed>(this).failure

fun FindUsagesResult.lookupFailure(): LookupFailure = assertIs<FindUsagesResult.LookupFailed>(this).failure

fun GetDefinitionResult.lookupFailure(): LookupFailure = assertIs<GetDefinitionResult.LookupFailed>(this).failure

fun ReadStructureResult.lookupFailure(): LookupFailure = assertIs<ReadStructureResult.LookupFailed>(this).failure

fun RenameResult.lookupFailure(): LookupFailure = assertIs<RenameResult.LookupFailed>(this).failure

fun EditResult.lookupFailure(): LookupFailure =
    assertIs<OperationFailure.LookupFailed>(assertIs<EditResult.OperationFailed>(this).failure).failure

/**
 * Ready unless [notReadyPhase] is given; then it stays in that phase until [becomeReady].
 * Every file has [documentSymbols], unless [symbolsByText] gives its exact text, or [symbolsByFile] the file, its own.
 * Workspace search returns [workspaceSymbols] whatever the query, unless an [index] is given: then it returns the
 * index entries whose name contains the query, ignoring case: kotlin-lsp 271.2085.0 matches substrings, not typos.
 * With a [searchCap], a search returns at most that many results, exact names first, possibly incomplete once it does.
 * Diagnostics are [diagnosticsByText] for the exact text, else none; each pull takes [diagnosticsDelay] and first runs
 * [beforeDiagnostics]; a pull of a text in [failingDiagnostics] fails. References are [references], and definitions [definitions], for the file and the exact position
 * asked about, else none. Organize-imports answers [organizedByText] for the exact text, else nothing.
 *
 * Documents are modelled like the language server sees them: a session's first analysis of a file opens it at
 * version 1 (unless another session holds it), an analysis of different text changes it to the next version, and a
 * file is closed once no session holds it. A rename at a file and position is [renames] for it, given the new name; one
 * at a position in [renameRefusals], or anywhere else, is refused (by prepare-rename and by rename alike). Keeping written files open after Write-back is the lsp adapter's business,
 * not modelled here. Using a session after it ended fails.
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
    private val failingDiagnostics: Set<String> = emptySet(),
    private val beforeDiagnostics: suspend () -> Unit = {},
    private val references: Map<Pair<Path, Position>, List<SourceLocation>> = emptyMap(),
    private val definitions: Map<Pair<Path, Position>, List<Definition>> = emptyMap(),
    private val renames: Map<Pair<Path, Position>, (newName: String) -> Rename> = emptyMap(),
    private val renameRefusals: Map<Pair<Path, Position>, String> = emptyMap(),
    private val organizedByText: Map<String, String> = emptyMap(),
    private val searchCap: Int? = null,
    /**
     * Diagnostics for a file's exact text given the text of every open document, ahead of [diagnosticsByText]: what
     * a file's diagnostics depend on beyond its own text. Null leaves it to [diagnosticsByText].
     */
    private val diagnosticsInContext: (file: Path, text: String, open: Map<Path, String>) -> List<Diagnostic>? = { _, _, _ -> null },
) : CodeIntelligence {
    val analysed = mutableListOf<Pair<Path, TextDocument>>()

    /** Every file whose diagnostics were pulled, in order. */
    val diagnosed = mutableListOf<Path>()

    /** Every file a references request was made in, in order. */
    val referenced = mutableListOf<Path>()
    val searched = mutableListOf<String>()

    /** What the language server was told, in order: `open <file> v1`, `change <file> v2`, `save <file>`, `close <file>`. */
    val events = mutableListOf<String>()

    /** Every session opened so far, in order. */
    val sessions = mutableListOf<SessionLog>()

    /**
     * One session: the files it [held] (analysed at least once), the ones it [closed] as their last holder, and
     * whether it has [ended]. With one session at a time, a session that ended closed everything it held.
     */
    class SessionLog {
        val held = mutableSetOf<Path>()
        val closed = mutableSetOf<Path>()
        var ended = false
    }

    private data class Open(val version: Int, val text: String, val holders: Int)

    private val open = mutableMapOf<Path, Open>()

    /** Documents some session analysed and that aren't closed yet. */
    val openDocuments: Set<Path> get() = open.keys

    /**
     * What ADR 0003 asks of one tool call: it opened at most one session, the session ended, and it closed every
     * document it opened, so nothing is left open. [message] names the case in a failure.
     */
    fun assertOneSessionClosedEverything(message: String? = null) {
        assertTrue(sessions.size <= 1, "${message.orEmpty()} opened ${sessions.size} sessions")
        sessions.forEach { session ->
            assertTrue(session.ended, "${message.orEmpty()} session never ended")
            assertEquals(session.held, session.closed, message)
        }
        assertEquals(emptySet(), openDocuments, message)
    }

    private val phase = MutableStateFlow(notReadyPhase)

    fun becomeReady() {
        phase.value = null
    }

    /** How many times a tool waited for readiness. */
    var readinessAwaits = 0

    override suspend fun awaitReady(timeout: Duration): Readiness {
        readinessAwaits++
        withTimeoutOrNull(timeout) { phase.first { it == null } }
        return phase.value?.let { Readiness.NotReady(it, elapsed = timeout) } ?: Readiness.Ready
    }

    override suspend fun <T> session(block: suspend (AnalysisSession) -> T): T {
        val session = Session(SessionLog().also { sessions += it })
        try {
            return block(session)
        } finally {
            session.end()
        }
    }

    /**
     * Like kotlin-lsp, stops at [searchCap] results, if given, listing names equal to the query first; a full page is
     * incomplete, for exact names too if it ends in one.
     */
    override suspend fun workspaceSymbols(query: String): SymbolSearch {
        searched += query
        val found = index?.filter { it.name.contains(query, ignoreCase = true) } ?: workspaceSymbols
        if (searchCap == null || found.size < searchCap) return SymbolSearch(found)
        val page = found.sortedBy { !it.name.equals(query, ignoreCase = true) }.take(searchCap)
        val exactCutOff = page.last().name.equals(query, ignoreCase = true)
        return SymbolSearch(page, if (exactCutOff) SymbolSearch.Completeness.INCOMPLETE else SymbolSearch.Completeness.EXACT_NAMES_COMPLETE)
    }

    private inner class Session(private val log: SessionLog) : AnalysisSession {
        override suspend fun documentSymbols(file: Path, document: TextDocument): List<CodeSymbol> {
            analysed += file to document
            sync(file, document)
            return symbolsByText[document.text] ?: symbolsByFile[file] ?: documentSymbols
        }

        override suspend fun diagnostics(file: Path, document: TextDocument): List<Diagnostic> {
            sync(file, document)
            diagnosed.add(file)
            beforeDiagnostics()
            delay(diagnosticsDelay)
            check(document.text !in failingDiagnostics) { "diagnostics failed" }
            return diagnosticsInContext(file, document.text, open.mapValues { it.value.text })
                ?: diagnosticsByText[document.text].orEmpty()
        }

        override suspend fun references(file: Path, document: TextDocument, position: Position): List<SourceLocation> {
            sync(file, document)
            referenced.add(file)
            return references[file to position].orEmpty()
        }

        override suspend fun definition(file: Path, document: TextDocument, position: Position): List<Definition> {
            sync(file, document)
            return definitions[file to position].orEmpty()
        }

        override suspend fun prepareRename(file: Path, document: TextDocument, position: Position): RenamePreparation {
            sync(file, document)
            renameRefusals[file to position]?.let { return RenamePreparation.Refused(it) }
            if (file to position !in renames) return RenamePreparation.Refused(CANNOT_RENAME)
            return RenamePreparation.Renameable(TextRange(position, position), placeholder = "")
        }

        override suspend fun rename(file: Path, document: TextDocument, position: Position, newName: String): Rename {
            sync(file, document)
            return renames[file to position]?.invoke(newName) ?: Rename.Refused(CANNOT_RENAME)
        }

        override suspend fun organizeImports(file: Path, document: TextDocument): TextDocument? {
            sync(file, document)
            return organizedByText[document.text]?.let(::TextDocument)
        }

        override suspend fun writtenBack(file: Path, document: TextDocument) {
            check(!log.ended) { "session used after it ended" }
            if (file in open) events += "save ${file.fileName}"
        }

        fun end() {
            log.ended = true
            log.held.forEach { file ->
                val current = open.getValue(file)
                if (current.holders > 1) {
                    open[file] = current.copy(holders = current.holders - 1)
                } else {
                    open.remove(file)
                    events += "close ${file.fileName}"
                    log.closed.add(file)
                }
            }
        }

        private fun sync(file: Path, document: TextDocument) {
            check(!log.ended) { "session used after it ended" }
            val current = open[file]
            val holders = (current?.holders ?: 0) + if (log.held.add(file)) 1 else 0
            open[file] = when {
                current == null -> Open(1, document.text, holders).also { events += "open ${file.fileName} v1" }
                current.text != document.text -> Open(current.version + 1, document.text, holders)
                    .also { events += "change ${file.fileName} v${it.version}" }
                else -> current.copy(holders = holders)
            }
        }
    }

    private companion object {
        const val CANNOT_RENAME = "This element cannot be renamed"
    }
}
