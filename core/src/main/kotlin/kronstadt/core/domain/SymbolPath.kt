package kronstadt.core.domain

/**
 * The address of one Symbol (ADR 0002): enclosing declaration names separated by `/`, e.g. `Outer/Nested/run`.
 *
 * A path may give only the trailing segments of a Symbol's chain; [anchored] (a leading `/`) pins it to the top level.
 * A segment may carry a parameter-type signature, `parse(String)`, compared to the language server's `detail`
 * after whitespace is removed: no type resolution, no positional index.
 */
public data class SymbolPath(val segments: List<Segment>, val anchored: Boolean = false) {
    init {
        require(segments.isNotEmpty()) { "a Symbol Path has at least one segment" }
    }

    public data class Segment(val name: String, val signature: String? = null) {
        override fun toString(): String = quote(name) + (signature?.let { "($it)" } ?: "")
    }

    /** The path in exactly the syntax [parse] accepts. */
    override fun toString(): String = (if (anchored) "/" else "") + segments.joinToString("/")

    public companion object {
        private val plainName = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")

        /** Names that aren't plain identifiers are written in backticks, as in Kotlin source. */
        private fun quote(name: String) = if (plainName.matches(name)) name else "`$name`"

        /** Parses [text], or explains why it isn't a Symbol Path. */
        public fun parse(text: String): Result<SymbolPath> = runCatching {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Symbol Path is empty" }
            val anchored = trimmed.startsWith("/")
            val segments = splitSegments(trimmed.removePrefix("/")).map(::parseSegment)
            SymbolPath(segments, anchored)
        }

        /** Splits at `/` outside parentheses and backticks. */
        private fun splitSegments(text: String): List<String> {
            val parts = mutableListOf<String>()
            val current = StringBuilder()
            var depth = 0
            var inBackticks = false
            for (c in text) {
                when {
                    c == '`' -> inBackticks = !inBackticks
                    inBackticks -> {}
                    c == '(' -> depth++
                    c == ')' -> {
                        depth--
                        require(depth >= 0) { "unbalanced ')' in '$text'" }
                    }
                    c == '/' && depth == 0 -> {
                        parts += current.toString()
                        current.clear()
                        continue
                    }
                }
                current.append(c)
            }
            require(!inBackticks) { "unclosed '`' in '$text'" }
            require(depth == 0) { "unbalanced parentheses in '$text'" }
            parts += current.toString()
            return parts
        }

        private fun parseSegment(text: String): Segment {
            val segment = text.trim()
            require(segment.isNotEmpty()) { "empty segment in Symbol Path (two '/' in a row, or a trailing '/')" }
            val open = indexOfSignature(segment)
            val rawName = if (open < 0) segment else segment.substring(0, open).trimEnd()
            val signature = if (open < 0) null else {
                require(segment.endsWith(")")) { "nothing may follow the signature in '$segment'" }
                segment.substring(open + 1, segment.length - 1)
            }
            val name = if (rawName.length >= 2 && rawName.startsWith("`") && rawName.endsWith("`")) {
                rawName.substring(1, rawName.length - 1)
            } else {
                rawName
            }
            require(name.isNotBlank()) { "segment '$segment' has no name" }
            return Segment(name, signature)
        }

        /** Index of the `(` that opens the signature, ignoring anything inside backticks. */
        private fun indexOfSignature(segment: String): Int {
            var inBackticks = false
            segment.forEachIndexed { i, c ->
                if (c == '`') inBackticks = !inBackticks
                if (c == '(' && !inBackticks) return i
            }
            return -1
        }
    }
}
