package kronstadt.core.domain

/**
 * One file's `documentSymbol` tree, against which Symbol Paths resolve exactly (ADR 0002), and which knows each
 * Symbol's canonical path: the shortest path that is unambiguous within the file.
 */
public class SymbolTree(roots: List<CodeSymbol>) {
    /** A Symbol with the chain of Symbols enclosing it, outermost first. */
    public class Node internal constructor(public val symbol: CodeSymbol, public val ancestors: List<CodeSymbol>) {
        internal val chain: List<CodeSymbol> = ancestors + symbol
    }

    public sealed interface Resolution {
        public data class Found(val node: Node) : Resolution
        public data class Ambiguous(val candidates: List<Node>) : Resolution
        public data class NotFound(val suggestions: List<Node>) : Resolution
    }

    /**
     * Every addressable Symbol, in source order with each container before its members. What sits inside a function,
     * constructor or property (accessors, local declarations, lambdas) isn't: edits target the enclosing declaration
     * (ADR 0002).
     */
    public val nodes: List<Node> = buildList {
        fun visit(symbol: CodeSymbol, ancestors: List<CodeSymbol>) {
            add(Node(symbol, ancestors))
            if (isFunction(symbol) || symbol.kind == SymbolKind.PROPERTY || symbol.kind == SymbolKind.VARIABLE) return
            symbol.children.sortedBy { it.range.start }.forEach { visit(it, ancestors + symbol) }
        }
        roots.sortedBy { it.range.start }.forEach { visit(it, emptyList()) }
    }

    public fun resolve(path: SymbolPath): Resolution {
        val matches = matching(path)
        return when (matches.size) {
            1 -> Resolution.Found(matches.single())
            0 -> Resolution.NotFound(suggestionsFor(path))
            else -> Resolution.Ambiguous(matches)
        }
    }

    /**
     * The shortest path that resolves to [node] alone: as few segments as possible, then the shortest text, so
     * signatures and a leading `/` appear only where names alone are ambiguous.
     */
    public fun canonicalPath(node: Node): SymbolPath {
        val chain = node.chain
        for (length in 1..chain.size) {
            val suffix = chain.takeLast(length)
            val segmentings = listOf(
                suffix.map { segment(it, withSignature = false) },
                suffix.mapIndexed { i, s -> segment(s, withSignature = i == suffix.lastIndex) },
                suffix.map { segment(it, withSignature = true) },
            )
            val anchorings = if (length == chain.size) listOf(false, true) else listOf(false)
            segmentings.flatMap { segments -> anchorings.map { SymbolPath(segments, it) } }
                .distinct()
                .filter { matching(it) == listOf(node) }
                .minByOrNull { it.toString().length }
                ?.let { return it }
        }
        // Nothing addresses this Symbol alone (two declarations a path can't tell apart): show the fullest form.
        return SymbolPath(chain.map { segment(it, withSignature = true) }, anchored = true)
    }

    private fun matching(path: SymbolPath): List<Node> {
        val candidates = nodes.filter { node ->
            val chain = node.chain
            val segments = path.segments
            if (segments.size > chain.size || (path.anchored && segments.size != chain.size)) return@filter false
            segments.indices.all { i -> matches(segments[i], chain[chain.size - segments.size + i]) }
        }
        if (path.segments.last().signature != null) return candidates
        // A bare name prefers a non-function to a function beside it (`size` the property, `size(Int)` the function).
        val nonFunctionParents = candidates.filterNot { isFunction(it.symbol) }.map { it.ancestors.lastOrNull() }
        return candidates.filterNot { node ->
            isFunction(node.symbol) && nonFunctionParents.any { it === node.ancestors.lastOrNull() }
        }
    }

    private fun matches(segment: SymbolPath.Segment, symbol: CodeSymbol): Boolean {
        if (segment.name != pathName(symbol)) return false
        if (segment.receiver?.let(::normalise) != symbol.receiver?.let(::normalise)) return false
        val wanted = segment.signature ?: return true
        val actual = symbol.parameterTypes ?: return false
        return normalise(wanted) == normalise(actual)
    }

    private fun segment(symbol: CodeSymbol, withSignature: Boolean) =
        SymbolPath.Segment(pathName(symbol), if (withSignature) symbol.parameterTypes else null, symbol.receiver)

    /** Suggestions for a path that matched nothing: Symbols whose name is close to the last segment's. */
    private fun suggestionsFor(path: SymbolPath): List<Node> {
        val wanted = path.segments.last().name
        return nodes
            .map { it to distance(wanted.lowercase(), pathName(it.symbol).lowercase()) }
            .filter { (_, d) -> d <= maxOf(1, wanted.length / 3) }
            .sortedBy { (_, d) -> d }
            .take(MAX_SUGGESTIONS)
            .map { (node, _) -> node }
    }

    internal companion object {
        private const val MAX_SUGGESTIONS = 5

        /** The name a Symbol has in a path. Constructors are `constructor`, not their class's name (ADR 0002). */
        private fun pathName(symbol: CodeSymbol): String = if (symbol.kind == SymbolKind.CONSTRUCTOR) "constructor" else symbol.name

        private fun isFunction(symbol: CodeSymbol) = symbol.kind == SymbolKind.FUNCTION || symbol.kind == SymbolKind.CONSTRUCTOR

        private fun normalise(signature: String): String = signature.filterNot(Char::isWhitespace)

        /** Levenshtein distance. */
        internal fun distance(a: String, b: String): Int {
            var previous = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val current = IntArray(b.length + 1)
                current[0] = i
                for (j in 1..b.length) {
                    val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                    current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
                }
                previous = current
            }
            return previous[b.length]
        }
    }
}
