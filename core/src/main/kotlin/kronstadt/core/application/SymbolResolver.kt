package kronstadt.core.application

import kronstadt.core.domain.AnalysedFile
import kronstadt.core.domain.PreferredOverFunctions
import kronstadt.core.domain.Suggestions
import kronstadt.core.domain.SymbolCandidate
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.packageName
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path

/**
 * A Symbol Path as the agent passed it, parsed: [symbol] as written, its [path], and the [file] to look in, if any.
 * Only [SymbolResolver.parse] makes one, so a package prefix never comes with a file.
 */
internal data class ParsedLookup(val symbol: String, val path: SymbolPath, val file: String?)

/**
 * The one lookup module every tool that accepts a Symbol Path goes through (ADR 0002): [parse] the path, [resolve] it
 * to exactly one Symbol, in a given file or, without one, project-wide, then, if the tool needs it, its [span]. Each
 * step answers a [LookupFailure] when it can't.
 *
 * It never waits for the project index: a tool that resolves project-wide awaits readiness once, up front.
 */
internal class SymbolResolver(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
) {
    /**
     * A resolved Symbol: its [node] in [analysed], whose canonical path is [path]. [preferred] is set when a bare name
     * chose it, a non-function, over same-named functions beside it (ADR 0002).
     */
    data class Found(
        val analysed: AnalysedFile,
        val node: SymbolTree.Node,
        val path: SymbolPath,
        val preferred: PreferredOverFunctions? = null,
    ) {
        val canonical: String get() = path.toString()
    }

    /**
     * Resolves [parsed] in its file or, without one, project-wide, narrowed to its package if it has one. Files are read
     * from the disk, or from [source] if given (the Shadow Workspace); since the index only sees the disk, a project-wide
     * lookup always reads the disk, and with a [source] is then resolved again in the file it found, as [source] has it.
     */
    suspend fun resolve(session: AnalysisSession, parsed: ParsedLookup, source: DocumentSource? = null): Outcome<Found> {
        val file = if (parsed.file != null) {
            files.classify(parsed.file).getOrElse { return Outcome.Failed(it) }
        } else {
            val found = resolveProjectWide(session, parsed).getOrElse { return Outcome.Failed(it) }
            if (source == null) return Outcome.Ok(found)
            found.analysed.file
        }
        return resolveIn(source ?: DocumentSource.disk(files, session), file, parsed)
            ?: Outcome.Failed(LookupFailure.FileNotFound(parsed.file ?: files.display(file)))
    }

    /** Resolves [parsed] in [file] as [source] has it; null if [source] has no such file. */
    suspend fun resolveIn(source: DocumentSource, file: Path, parsed: ParsedLookup): Outcome<Found>? {
        val analysed = source.analysed(file) ?: return null
        return when (val resolution = analysed.resolve(parsed.path)) {
            is AnalysedFile.Resolution.Found -> Outcome.Ok(Found(analysed, resolution.node, resolution.path, resolution.preferred))
            is AnalysedFile.Resolution.Ambiguous ->
                Outcome.Failed(LookupFailure.Ambiguous(parsed.symbol, analysed.shown, resolution.candidates))
            is AnalysedFile.Resolution.NotFound ->
                Outcome.Failed(LookupFailure.NotFound(parsed.symbol, analysed.shown, resolution.suggestions))
        }
    }

    /** The Symbol Span of [found], whole lines a replace or delete acts on, unless its range can't be trusted. */
    fun span(found: Found): Outcome<SymbolSpan> = when (val computation = found.analysed.span(found.node)) {
        is SymbolSpan.Computation.Computed -> Outcome.Ok(computation.span)
        SymbolSpan.Computation.OutOfSync -> Outcome.Failed(LookupFailure.OutOfSync(found.canonical, found.analysed.shown))
        is SymbolSpan.Computation.SharedLine ->
            Outcome.Failed(LookupFailure.SharedLine(found.canonical, found.analysed.shown, computation.line + 1))
    }

    /**
     * `workspace/symbol` only supplies candidate files: kotlin-lsp's index is lossy, so each candidate is resolved
     * through its own `documentSymbol` tree. A search that may have left out a symbol of that exact name resolves nothing.
     */
    private suspend fun resolveProjectWide(session: AnalysisSession, parsed: ParsedLookup): Outcome<Found> {
        val symbol = parsed.path
        // Constructors and unnamed companions aren't indexed under those names; the class before them is.
        val query = symbol.segments.lastOrNull { it.name !in UNINDEXED_NAMES }?.name
            ?: return Outcome.Failed(LookupFailure.NotFound(parsed.symbol, null, emptyList()))
        val search = intelligence.workspaceSymbols(query)
        // Not a miss: the index said nothing at all, so neither "not found" nor suggestions would be true.
        if (search.completeness == SymbolSearch.Completeness.TIMED_OUT) {
            return Outcome.Failed(LookupFailure.SearchTimedOut(parsed.symbol, query))
        }
        // A file the index left out may declare the name too: resolving from the rest would pick one (ADR 0002).
        if (search.completeness == SymbolSearch.Completeness.INCOMPLETE) {
            return Outcome.Failed(LookupFailure.TooManyDeclarations(parsed.symbol, query))
        }
        val hits = search.symbols.filter { AnalysedFile.isKotlin(it.file) }
        val cache = mutableMapOf<Path, AnalysedFile?>()
        val analysed: suspend (Path) -> AnalysedFile? = { file -> cache.getOrPut(file) { session.analyse(files, file) } }

        val matches = hits.filter { it.name == query }.map { it.file }.distinct().sorted().flatMap { file ->
            val candidate = analysed(file)?.takeIf { inPackage(it, symbol) } ?: return@flatMap emptyList()
            when (val resolution = candidate.resolve(symbol)) {
                is AnalysedFile.Resolution.Found -> listOf(Found(candidate, resolution.node, resolution.path, resolution.preferred))
                is AnalysedFile.Resolution.Ambiguous -> resolution.matches.map { Found(candidate, it, candidate.path(it)) }
                is AnalysedFile.Resolution.NotFound -> emptyList()
            }
        }
        if (matches.size == 1) return Outcome.Ok(matches.single())
        if (matches.isNotEmpty()) {
            return Outcome.Failed(LookupFailure.Ambiguous(parsed.symbol, null, matches.map { it.analysed.candidate(it.node) }))
        }
        return Outcome.Failed(LookupFailure.NotFound(parsed.symbol, null, suggestionsProjectWide(symbol, query, hits, analysed)))
    }

    /**
     * Close names for [symbol], which matched nothing project-wide: they live in the files the index found for names
     * like [query] ([hits] and more), closest first. The index matches substrings, not typos, so a prefix of the name is
     * looked up too. Suggestions are hints, not resolutions, so a search that may be incomplete still supplies them.
     */
    private suspend fun suggestionsProjectWide(
        symbol: SymbolPath,
        query: String,
        hits: List<SymbolLocation>,
        analysed: suspend (Path) -> AnalysedFile?,
    ): List<SymbolCandidate> {
        val prefix = query.take(maxOf(MIN_PREFIX, query.length / 2))
        val similar = hits + if (prefix != query) intelligence.workspaceSymbols(prefix).symbols.filter { AnalysedFile.isKotlin(it.file) } else emptyList()
        val suggestionFiles = similar.sortedBy { Suggestions.distance(it.name.lowercase(), query.lowercase()) }
            .map { it.file }.distinct().take(Suggestions.MAX)
        val wanted = symbol.segments.last().name.lowercase()
        // Outside the wanted package too, so a wrong package still finds the Symbol.
        val unscoped = if (symbol.packageName == null) symbol else symbol.copy(anchored = false, packageName = null)
        val suggestions = suggestionFiles.flatMap { file ->
            val candidate = analysed(file) ?: return@flatMap emptyList()
            val nodes = when (val resolution = candidate.resolve(unscoped)) {
                is AnalysedFile.Resolution.NotFound -> resolution.close
                is AnalysedFile.Resolution.Found -> listOf(resolution.node)
                is AnalysedFile.Resolution.Ambiguous -> resolution.matches
            }
            nodes.map { candidate.suggestion(it, unscoped) to Suggestions.distance(wanted, it.symbol.name.lowercase()) }
        }.sortedBy { (_, distance) -> distance }.map { (suggestion, _) -> suggestion }
        return suggestions.take(Suggestions.MAX)
    }

    private fun inPackage(analysed: AnalysedFile, symbol: SymbolPath) =
        symbol.packageName == null || analysed.document.packageName == symbol.packageName

    companion object {
        private val UNINDEXED_NAMES = setOf("constructor", "Companion")
        private const val MIN_PREFIX = 3

        /**
         * Parses [symbol], to be looked up in [file] or, without one, project-wide. A package prefix narrows a
         * project-wide lookup only: with a file it is an [LookupFailure.InvalidPath].
         */
        fun parse(file: String?, symbol: String): Outcome<ParsedLookup> {
            val path = SymbolPath.parse(symbol).getOrElse { return Outcome.Failed(LookupFailure.InvalidPath(symbol, it.message.orEmpty())) }
            if (file != null && path.packageName != null) {
                return Outcome.Failed(LookupFailure.InvalidPath(symbol, "a package prefix is only for paths without a file; drop it or the file"))
            }
            return Outcome.Ok(ParsedLookup(symbol, path, file))
        }
    }
}

/**
 * The Kotlin file in the project that the agent's [path] names, if it exists: the one place these checks run, in this
 * order, for every tool that takes a file.
 */
internal suspend fun FileStore.classify(path: String): Outcome<Path> {
    val file = resolve(path) ?: return Outcome.Failed(LookupFailure.OutsideProject(path))
    if (!AnalysedFile.isKotlin(file)) return Outcome.Failed(LookupFailure.NotKotlin(path))
    if (read(file) == null) return Outcome.Failed(LookupFailure.FileNotFound(path))
    return Outcome.Ok(file)
}
