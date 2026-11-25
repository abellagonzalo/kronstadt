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
        /**
         * It names [node] alone. If a bare name chose it, a non-function, over same-named functions beside it (ADR 0002),
         * [passedOver] are those functions; else it is empty.
         */
        public data class Found(val node: Node, val passedOver: List<Node> = emptyList()) : Resolution
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
        val (matches, passedOver) = matchesAndPassedOver(path)
        return when (matches.size) {
            1 -> Resolution.Found(matches.single(), passedOver)
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
        for (length in 1..chain.size) shortestNaming(node, length)?.let { return it }
        // Nothing addresses this Symbol alone (two declarations a path can't tell apart): show the fullest form.
        return SymbolPath(chain.map { segment(it, withSignature = true) }, anchored = true)
    }

    /**
     * The shortest-text path of the last [length] Symbols of [node]'s chain that resolves to [node] alone: signatures and
     * a leading `/` only where needed. Null if none does.
     */
    private fun shortestNaming(node: Node, length: Int): SymbolPath? {
        val chain = node.chain
        val suffix = chain.takeLast(length)
        val segmentings = listOf(
            suffix.map { segment(it, withSignature = false) },
            suffix.mapIndexed { i, s -> segment(s, withSignature = i == suffix.lastIndex) },
            suffix.map { segment(it, withSignature = true) },
        )
        val anchorings = if (length == chain.size) listOf(false, true) else listOf(false)
        return segmentings.flatMap { segments -> anchorings.map { SymbolPath(segments, it) } }
            .distinct()
            .filter { matching(it) == listOf(node) }
            .minByOrNull { it.toString().length }
    }

    /**
     * How a close name for [missed], a path that didn't name [node], shows [node]: its canonical path, unless [missed] has
     * more segments. Then it is the shortest path that resolves to [node] alone among those naming its enclosing chain
     * back to as many segments as [missed] had, or, if [missed] starts with the name of a Symbol enclosing [node], back to
     * that Symbol: `FileLayout/Companion/of` for `FileLayout/of`. The longer path shows where [node] lives.
     */
    public fun suggestionPath(node: Node, missed: SymbolPath): SymbolPath {
        val canonical = canonicalPath(node)
        if (missed.segments.size <= canonical.segments.size) return canonical
        // node.chain is node.ancestors + node, so an ancestor at index i starts the chain's last (chain.size - i) Symbols.
        val namedAncestor = node.ancestors.indexOfLast { pathName(it) == missed.segments.first().name }
        val backToNamed = if (namedAncestor < 0) 0 else node.chain.size - namedAncestor
        val length = maxOf(missed.segments.size, backToNamed).coerceAtMost(node.chain.size)
        return shortestNaming(node, length) ?: canonical
    }

    private fun matching(path: SymbolPath): List<Node> = matchesAndPassedOver(path).first

    /** The Symbols [path] names, then the functions a bare name passed over for a non-function beside them. */
    private fun matchesAndPassedOver(path: SymbolPath): Pair<List<Node>, List<Node>> {
        val candidates = nodes.filter { node ->
            val chain = node.chain
            val segments = path.segments
            if (segments.size > chain.size || (path.anchored && segments.size != chain.size)) return@filter false
            segments.indices.all { i -> matches(segments[i], chain[chain.size - segments.size + i]) }
        }
        if (path.segments.last().signature != null) return candidates to emptyList()
        // A bare name prefers a non-function to a function beside it (`size` the property, `size(Int)` the function).
        val nonFunctionParents = candidates.filterNot { isFunction(it.symbol) }.map { it.ancestors.lastOrNull() }
        return candidates.partition { node ->
            !(isFunction(node.symbol) && nonFunctionParents.any { it === node.ancestors.lastOrNull() })
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
            .map { it to Suggestions.distance(wanted.lowercase(), pathName(it.symbol).lowercase()) }
            .filter { (_, d) -> d <= maxOf(1, wanted.length / 3) }
            .sortedBy { (_, d) -> d }
            .take(Suggestions.MAX)
            .map { (node, _) -> node }
    }

    private companion object {
        /** The name a Symbol has in a path. Constructors are `constructor`, not their class's name (ADR 0002). */
        fun pathName(symbol: CodeSymbol): String = if (symbol.kind == SymbolKind.CONSTRUCTOR) "constructor" else symbol.name

        fun isFunction(symbol: CodeSymbol) = symbol.kind == SymbolKind.FUNCTION || symbol.kind == SymbolKind.CONSTRUCTOR

        fun normalise(signature: String): String = signature.filterNot(Char::isWhitespace)
    }
}
