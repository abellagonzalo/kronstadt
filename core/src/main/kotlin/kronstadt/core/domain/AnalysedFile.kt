package kronstadt.core.domain

import java.nio.file.Path

/**
 * One file's Symbols: its text, as [document], and the `documentSymbol` tree the language server reported for it. It is
 * where a Symbol in a file gets its canonical Symbol Path, the lines a listing shows for it and its Symbol Span, and where
 * a Symbol Path resolves within the file (ADR 0002). [shown] is [file] as the tools show it.
 */
public class AnalysedFile(
    public val file: Path,
    public val shown: String,
    public val document: TextDocument,
    symbols: List<CodeSymbol>,
) {
    private val tree = SymbolTree(symbols)
    private val paths = HashMap<SymbolTree.Node, SymbolPath>()

    /**
     * Every addressable Symbol, in source order with each container before its members. What sits inside a function,
     * constructor or property (accessors, local declarations, lambdas) isn't (ADR 0002).
     */
    public val nodes: List<SymbolTree.Node> get() = tree.nodes

    /** How a Symbol Path resolves within this file. */
    public sealed interface Resolution {
        /**
         * It names [node] alone, whose canonical path is [path]. If a bare name chose it, a non-function, over same-named
         * functions beside it (ADR 0002), [passedOver] are their canonical paths; else it is empty.
         */
        public data class Found(val node: SymbolTree.Node, val path: SymbolPath, val passedOver: List<SymbolPath> = emptyList()) : Resolution {
            /** The non-function preference's choice, when it decided this resolution; null otherwise. */
            val preferred: PreferredOverFunctions?
                get() = passedOver.takeIf { it.isNotEmpty() }?.let { paths -> PreferredOverFunctions(node.symbol.kind, paths.map { it.toString() }) }
        }

        /** It names each of [matches], shown as [candidates]. */
        public data class Ambiguous(val matches: List<SymbolTree.Node>, val candidates: List<SymbolCandidate>) : Resolution

        /** It names nothing; [close] are Symbols with a close name, shown as [suggestions]. */
        public data class NotFound(val close: List<SymbolTree.Node>, val suggestions: List<SymbolCandidate>) : Resolution
    }

    public fun resolve(path: SymbolPath): Resolution = when (val resolution = tree.resolve(path)) {
        is SymbolTree.Resolution.Found -> Resolution.Found(resolution.node, path(resolution.node), resolution.passedOver.map(::path))
        is SymbolTree.Resolution.Ambiguous -> Resolution.Ambiguous(resolution.candidates, resolution.candidates.map(::candidate))
        is SymbolTree.Resolution.NotFound -> Resolution.NotFound(resolution.suggestions, resolution.suggestions.map { suggestion(it, path) })
    }

    /** [node]'s canonical Symbol Path: the shortest that resolves to it alone in this file. */
    public fun path(node: SymbolTree.Node): SymbolPath = paths.getOrPut(node) { tree.canonicalPath(node) }

    /** [node] as a lookup failure names it: its canonical path, in this file. */
    public fun candidate(node: SymbolTree.Node): SymbolCandidate = SymbolCandidate(path(node).toString(), shown)

    /**
     * [node] as a close name for [missed], a path that matched nothing, in this file: its canonical path, or a longer one
     * showing its enclosing chain when [missed] has more segments ([SymbolTree.suggestionPath]).
     */
    public fun suggestion(node: SymbolTree.Node, missed: SymbolPath): SymbolCandidate =
        SymbolCandidate(tree.suggestionPath(node, missed).toString(), shown)

    /** [node]'s Symbol Span, the whole lines an Operation acts on, unless its range can't be trusted. */
    public fun span(node: SymbolTree.Node): SymbolSpan.Computation = SymbolSpan.of(document, node.symbol)

    /**
     * The 0-based lines a listing shows for [node]: its Symbol Span's, as `kt_read_symbol` reports them, or its
     * declaration's when it has no whole-line span (it shares a line with other code).
     */
    public fun listedLines(node: SymbolTree.Node): IntRange = SymbolSpan.lines(document, node.symbol).let { (first, last) -> first..last }

    /** [node] as listings show it. */
    public fun listing(node: SymbolTree.Node): SymbolListing {
        val lines = listedLines(node)
        val symbol = node.symbol
        return SymbolListing(path(node), symbol.kind, symbol.type, node.ancestors.size, lines.first + 1, lines.last + 1)
    }

    /** Every addressable Symbol as listings show it, in the order of [nodes]. */
    public val listings: List<SymbolListing> get() = nodes.map(::listing)

    /**
     * The Symbol Span of the innermost addressable Symbol whose span holds every one of [lines] (0-based); null if none
     * does. Symbols without a Symbol Span (they share a line with other code) are skipped, as an Operation can't target
     * them.
     */
    public fun innermostSpanHolding(lines: IntRange): SymbolSpan? = nodes
        .mapNotNull { node -> (span(node) as? SymbolSpan.Computation.Computed)?.let { node to it.span } }
        .filter { (_, span) -> span.firstLine <= lines.first && lines.last <= span.lastLine }
        .maxByOrNull { (node, _) -> node.ancestors.size }
        ?.second

    public companion object {
        /** Whether [file] is a Kotlin source or script, by its name. */
        public fun isKotlin(file: Path): Boolean = file.fileName.toString().let { it.endsWith(".kt") || it.endsWith(".kts") }
    }
}

/**
 * A Symbol as listings show it: its canonical [path], [kind], [signature] (its type as the language server shows it),
 * [depth] (how many Symbols enclose it) and the 1-based [firstLine] and [lastLine] of [AnalysedFile.listedLines].
 */
public data class SymbolListing(
    val path: SymbolPath,
    val kind: SymbolKind,
    val signature: String?,
    val depth: Int,
    val firstLine: Int,
    val lastLine: Int,
)

/**
 * A Symbol a lookup failure names: its Symbol [path], canonical except for a close name shown with more of its enclosing
 * chain ([AnalysedFile.suggestion]), and its [file] as shown.
 */
public data class SymbolCandidate(val path: String, val file: String)
