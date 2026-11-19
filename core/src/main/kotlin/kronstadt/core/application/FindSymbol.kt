package kronstadt.core.application

import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.SymbolTree
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Use case behind `kt_find_symbol`: locate declarations by name without knowing their file.
 *
 * The index only finds names: each hit is looked up in its file's `documentSymbol` tree, which gives its canonical
 * Symbol Path and drops what isn't an addressable Symbol (locals, accessors). Hits outside the project's Kotlin
 * files are dropped too.
 *
 * Waits up to [readinessTimeout] for a complete index first: an incomplete one silently returns fewer symbols,
 * which would be indistinguishable from a real miss.
 */
public class FindSymbol(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
) {
    public suspend operator fun invoke(query: String, kind: SymbolKind? = null, limit: Int = DEFAULT_LIMIT): FindSymbolResult {
        if (query.isBlank()) return FindSymbolResult.InvalidQuery("query must not be blank")
        if (limit < 1) return FindSymbolResult.InvalidQuery("limit must be at least 1, was $limit")
        val readiness = intelligence.awaitReady(readinessTimeout)
        if (readiness is Readiness.NotReady) return FindSymbolResult.NotReady(readiness)
        val search = intelligence.workspaceSymbols(query)
        val hits = search.symbols
            .filter { (kind == null || it.kind == kind) && inProjectSource(it.file) }
            // Exact name matches first, then prefix matches, then everything else the server considered relevant.
            .sortedWith(compareBy<SymbolLocation> { rank(it.name, query) }.thenBy { it.name }.thenBy { it.file })

        // Files are analysed lazily, in rank order, only until one result more than the limit is known.
        val analysed = mutableMapOf<Path, AnalysedFile?>()
        val matches = mutableListOf<Pair<Path, FoundSymbol>>()
        intelligence.session { session ->
            for (hit in hits) {
                if (matches.size > limit) break
                val file = analysed.getOrPut(hit.file) { AnalysedFile.of(files, session, hit.file) } ?: continue
                val node = nodeAt(file.tree, hit) ?: continue
                val (first, last) = SymbolSpan.lines(file.document, node.symbol)
                val found = FoundSymbol(node.symbol.kind, file.tree.canonicalPath(node).toString(), first + 1, last + 1)
                if (matches.none { (f, s) -> f == hit.file && s == found }) matches += hit.file to found
            }
        }
        if (matches.isEmpty()) return FindSymbolResult.NoMatches(query, kind, search.mayBeIncomplete)
        val shown = matches.take(limit)
            .groupBy({ (file, _) -> file }, { (_, symbol) -> symbol })
            .map { (file, symbols) -> FileMatches(files.display(file), symbols) }
        return FindSymbolResult.Found(shown, truncated = matches.size > limit, mayBeIncomplete = search.mayBeIncomplete)
    }

    private fun inProjectSource(file: Path) = AnalysedFile.isKotlin(file) && files.resolve(file.toString()) == file

    /**
     * The addressable Symbol the index hit names: the innermost one with that name whose name range holds the hit's
     * start (the index reports name ranges). Null when there is none, e.g. for a local declaration.
     */
    private fun nodeAt(tree: SymbolTree, hit: SymbolLocation): SymbolTree.Node? =
        tree.nodes.lastOrNull { it.symbol.name == hit.name && (hit.range.start in it.symbol.selectionRange || hit.range.start == it.symbol.selectionRange.start) }

    private fun rank(name: String, query: String): Int = when {
        name == query -> 0
        name.equals(query, ignoreCase = true) -> 1
        name.startsWith(query, ignoreCase = true) -> 2
        else -> 3
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 50
    }
}

/** A declaration found by name: its kind, canonical Symbol Path within its file, and 1-based line span. */
public data class FoundSymbol(val kind: SymbolKind, val path: String, val firstLine: Int, val lastLine: Int)

/** The declarations found in one file, [file] as shown to the agent, best-ranked first. */
public data class FileMatches(val file: String, val symbols: List<FoundSymbol>)

public sealed interface FindSymbolResult {
    /**
     * Files in the order of their best-ranked declaration. [mayBeIncomplete] when the index may have left matches out,
     * however high the limit: a narrower query may find them.
     */
    public data class Found(val files: List<FileMatches>, val truncated: Boolean, val mayBeIncomplete: Boolean) : FindSymbolResult

    /** [mayBeIncomplete] when the index may have left matches out: a narrower query may find some. */
    public data class NoMatches(val query: String, val kind: SymbolKind?, val mayBeIncomplete: Boolean) : FindSymbolResult
    public data class InvalidQuery(val reason: String) : FindSymbolResult

    /** The index was still incomplete after waiting; nothing was searched. */
    public data class NotReady(val readiness: Readiness.NotReady) : FindSymbolResult
}
