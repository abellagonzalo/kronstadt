package kronstadt.core.domain

/**
 * Renders a token-cheap skeleton of a file: every addressable Symbol, without bodies, nested under its container.
 *
 * Each entry shows the Symbol's canonical Symbol Path (exactly what the tools accept), its type as the language
 * server shows it, without an extension receiver (so overloads look different), and its 1-based line span, as `kt_read_symbol` reports it.
 */
public object StructureRenderer {
    public fun render(file: AnalysedFile, xml: Boolean): String {
        val body = file.listings.joinToString("\n", transform = ::entry)
        if (body.isEmpty()) return ""
        // The tags are delimiters for the model, not a document to be parsed: the body stays unescaped so
        // signatures like `List<User>` read naturally.
        return if (xml) "<file_structure path=\"${escapeAttribute(file.shown)}\">\n$body\n</file_structure>" else body
    }

    private fun entry(listing: SymbolListing): String = buildString {
        append("  ".repeat(listing.depth))
        // A constructor's path already says `constructor`.
        if (listing.kind != SymbolKind.CONSTRUCTOR) append(listing.kind.keyword).append(' ')
        val path = listing.path
        append(path)
        // A constructor's signature is only its parameter list, which a path that needs it already shows.
        listing.signature
            ?.takeUnless { it == path.segments.last().signature?.let { params -> "($params)" } }
            ?.let { append("  ").append(it) }
        append("  // L").append(listing.firstLine).append('-').append(listing.lastLine)
    }

    private fun escapeAttribute(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
