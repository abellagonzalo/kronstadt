package kronstadt.core.domain

/**
 * Renders a token-cheap skeleton of a file: every addressable Symbol, without bodies, nested under its container.
 *
 * Each entry shows the Symbol's canonical Symbol Path (exactly what the tools accept), its signature from the
 * language server's `detail` (so overloads look different), and its 1-based line span, as `kt_read_symbol` reports it.
 */
public object StructureRenderer {
    public fun render(path: String, document: TextDocument, symbols: List<CodeSymbol>, xml: Boolean): String {
        val tree = SymbolTree(symbols)
        val body = tree.nodes.joinToString("\n") { entry(document, tree, it) }
        if (body.isEmpty()) return ""
        // The tags are delimiters for the model, not a document to be parsed: the body stays unescaped so
        // signatures like `List<User>` read naturally.
        return if (xml) "<file_structure path=\"${escapeAttribute(path)}\">\n$body\n</file_structure>" else body
    }

    private fun entry(document: TextDocument, tree: SymbolTree, node: SymbolTree.Node): String = buildString {
        val symbol = node.symbol
        append("  ".repeat(node.ancestors.size))
        // A constructor's path already says `constructor`.
        if (symbol.kind != SymbolKind.CONSTRUCTOR) append(symbol.kind.keyword).append(' ')
        val path = tree.canonicalPath(node)
        append(path)
        // A constructor's signature is only its parameter list, which a path that needs it already shows.
        tree.signature(node)
            ?.takeUnless { it == path.segments.last().signature?.let { params -> "($params)" } }
            ?.let { append("  ").append(it) }
        val (first, last) = lines(document, symbol)
        append("  // L").append(first + 1).append('-').append(last + 1)
    }

    /**
     * The Symbol Span's lines, as `kt_read_symbol` reports them. A Symbol without a whole-line span (it shares a line
     * with other code) reports its declaration's lines.
     */
    private fun lines(document: TextDocument, symbol: CodeSymbol): Pair<Int, Int> =
        when (val computation = SymbolSpan.of(document, symbol)) {
            is SymbolSpan.Computation.Computed -> computation.span.firstLine to computation.span.lastLine
            else -> symbol.range.start.line to symbol.range.end.line
        }

    private fun escapeAttribute(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
