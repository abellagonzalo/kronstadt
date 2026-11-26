package kronstadt.server

import kronstadt.core.domain.AnalysedFile
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolListing

/**
 * Renders a token-cheap skeleton of a file: every addressable Symbol, without bodies, nested under its container.
 *
 * Each entry shows the Symbol's canonical Symbol Path (exactly what the tools accept), its type as the language
 * server shows it, without an extension receiver (so overloads look different), and its 1-based line span, as `kt_read_symbol` reports it.
 */
internal object StructureRenderer {
    /** The skeleton under a `// <file>` header line, or nothing at all for a file without declarations. */
    fun render(file: AnalysedFile): String {
        val body = file.listings.joinToString("\n", transform = ::entry)
        if (body.isEmpty()) return ""
        return "// ${file.shown}\n$body"
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
}
