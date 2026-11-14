package kronstadt.core.domain

/**
 * Renders a token-cheap skeleton of a file: containers and their member declarations,
 * without function bodies.
 *
 * Each entry shows the declaration's header line taken verbatim from the source (so it reflects real
 * modifiers and signatures, independent of how a given language server formats symbol details),
 * followed by its 1-based line span so the agent can read just that slice if needed.
 */
public object StructureRenderer {
    public fun render(path: String, document: TextDocument, symbols: List<CodeSymbol>, xml: Boolean): String {
        val body = buildString {
            symbols.sortedBy { it.range.start }.forEach { appendSymbol(document, it, depth = 0) }
        }.trimEnd()
        // The tags are delimiters for the model, not a document to be parsed: the body stays unescaped so
        // signatures like `List<User>` read naturally.
        return if (xml) "<file_structure path=\"${escapeAttribute(path)}\">\n$body\n</file_structure>" else body
    }

    private fun StringBuilder.appendSymbol(document: TextDocument, symbol: CodeSymbol, depth: Int) {
        append("  ".repeat(depth))
        append(header(document, symbol))
        append("  // L").append(symbol.range.start.line + 1).append('-').append(symbol.range.end.line + 1)
        append('\n')
        // Members of functions (local variables, lambdas) are implementation detail: skip them.
        if (symbol.kind.isContainer) {
            symbol.children.sortedBy { it.range.start }.forEach { appendSymbol(document, it, depth + 1) }
        }
    }

    /** The source line holding the symbol's name, with any trailing body opener stripped. */
    private fun header(document: TextDocument, symbol: CodeSymbol): String {
        val line = document.line(symbol.selectionRange.start.line).trim()
        if (line.isEmpty()) return "${symbol.kind.keyword} ${symbol.name}"
        return line.removeSuffix("{").trimEnd()
    }

    private fun escapeAttribute(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
