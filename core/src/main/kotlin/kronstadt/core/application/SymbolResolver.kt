package kronstadt.core.application

import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.packageName
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/** A Symbol a path could mean: its canonical Symbol Path within [file], and [file] as shown to the agent. */
public data class SymbolCandidate(val path: String, val file: String)

/**
 * Resolves a Symbol Path to exactly one Symbol (ADR 0002), in a given file or, without one, project-wide.
 *
 * Every tool that accepts a Symbol Path resolves it here. Each file analysed is closed again before this returns.
 */
internal class SymbolResolver(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration,
) {
    sealed interface Resolution {
        data class Found(val file: Path, val document: TextDocument, val tree: SymbolTree, val node: SymbolTree.Node) : Resolution
        data class Ambiguous(val candidates: List<SymbolCandidate>) : Resolution
        data class NotFound(val suggestions: List<SymbolCandidate>) : Resolution
        data class NotReady(val phase: String, val elapsed: Duration) : Resolution
        data class FileNotFound(val path: String) : Resolution
        data class NotKotlin(val path: String) : Resolution
        data class OutsideProject(val path: String) : Resolution
    }

    /** Resolves [symbol] in [path]; without a [path], project-wide, narrowed to its package if it has one. */
    suspend fun resolve(path: String?, symbol: SymbolPath): Resolution =
        if (path == null) resolveProjectWide(symbol) else resolveInFile(path, symbol)

    /** Syntax-only: answers from the file's `documentSymbol` tree without waiting for the project index. */
    private suspend fun resolveInFile(path: String, symbol: SymbolPath): Resolution {
        val file = files.resolve(path) ?: return Resolution.OutsideProject(path)
        if (!AnalysedFile.isKotlin(file)) return Resolution.NotKotlin(path)
        val analysed = AnalysedFile.of(files, intelligence, file) ?: return Resolution.FileNotFound(path)
        return when (val resolution = analysed.tree.resolve(symbol)) {
            is SymbolTree.Resolution.Found -> found(analysed, resolution.node)
            is SymbolTree.Resolution.Ambiguous -> Resolution.Ambiguous(resolution.candidates.map { candidate(analysed, it) })
            is SymbolTree.Resolution.NotFound -> Resolution.NotFound(resolution.suggestions.map { candidate(analysed, it) })
        }
    }

    /**
     * `workspace/symbol` only supplies candidate files: kotlin-lsp's index is lossy, so each candidate is resolved
     * through its own `documentSymbol` tree. Waits for a complete index first, since an incomplete one would make a
     * Symbol look missing.
     */
    private suspend fun resolveProjectWide(symbol: SymbolPath): Resolution {
        val readiness = intelligence.awaitReady(readinessTimeout)
        if (readiness is Readiness.NotReady) return Resolution.NotReady(readiness.phase, readiness.elapsed)
        // Constructors and unnamed companions aren't indexed under those names; the class before them is.
        val query = symbol.segments.lastOrNull { it.name !in UNINDEXED_NAMES }?.name ?: return Resolution.NotFound(emptyList())
        val hits = intelligence.workspaceSymbols(query).filter { AnalysedFile.isKotlin(it.file) }
        val analysed = mutableMapOf<Path, AnalysedFile?>()
        suspend fun analysedOrNull(file: Path) = analysed.getOrPut(file) { AnalysedFile.of(files, intelligence, file) }

        val matches = hits.filter { it.name == query }.map { it.file }.distinct().sorted().flatMap { file ->
            val candidate = analysedOrNull(file)?.takeIf { inPackage(it, symbol) } ?: return@flatMap emptyList()
            when (val resolution = candidate.tree.resolve(symbol)) {
                is SymbolTree.Resolution.Found -> listOf(candidate to resolution.node)
                is SymbolTree.Resolution.Ambiguous -> resolution.candidates.map { candidate to it }
                is SymbolTree.Resolution.NotFound -> emptyList()
            }
        }
        if (matches.size == 1) return matches.single().let { (file, node) -> found(file, node) }
        if (matches.isNotEmpty()) return Resolution.Ambiguous(matches.map { (file, node) -> candidate(file, node) })

        // Close names live in the files the index found for names like the wanted one, closest first. The index
        // matches substrings, not typos, so a prefix of the name is looked up too.
        val prefix = query.take(maxOf(MIN_PREFIX, query.length / 2))
        val similar = hits + if (prefix != query) intelligence.workspaceSymbols(prefix).filter { AnalysedFile.isKotlin(it.file) } else emptyList()
        val suggestionFiles = similar.sortedBy { SymbolTree.distance(it.name.lowercase(), query.lowercase()) }
            .map { it.file }.distinct().take(MAX_SUGGESTIONS)
        val wanted = symbol.segments.last().name.lowercase()
        val suggestions = suggestionFiles.flatMap { file ->
            val candidate = analysedOrNull(file) ?: return@flatMap emptyList()
            // Outside the wanted package too, so a wrong package still finds the Symbol.
            val unscoped = if (symbol.packageName == null) symbol else symbol.copy(anchored = false, packageName = null)
            val nodes = when (val resolution = candidate.tree.resolve(unscoped)) {
                is SymbolTree.Resolution.NotFound -> resolution.suggestions
                is SymbolTree.Resolution.Found -> listOf(resolution.node)
                is SymbolTree.Resolution.Ambiguous -> resolution.candidates
            }
            nodes.map { candidate(candidate, it) to SymbolTree.distance(wanted, it.symbol.name.lowercase()) }
        }.sortedBy { (_, distance) -> distance }.map { (suggestion, _) -> suggestion }
        return Resolution.NotFound(suggestions.take(MAX_SUGGESTIONS))
    }

    private fun found(analysed: AnalysedFile, node: SymbolTree.Node) =
        Resolution.Found(analysed.file, analysed.document, analysed.tree, node)

    private fun candidate(analysed: AnalysedFile, node: SymbolTree.Node) =
        SymbolCandidate(analysed.tree.canonicalPath(node).toString(), files.display(analysed.file))

    private fun inPackage(analysed: AnalysedFile, symbol: SymbolPath) =
        symbol.packageName == null || analysed.document.packageName == symbol.packageName

    private companion object {
        val UNINDEXED_NAMES = setOf("constructor", "Companion")
        const val MIN_PREFIX = 3
        const val MAX_SUGGESTIONS = 5
    }
}
