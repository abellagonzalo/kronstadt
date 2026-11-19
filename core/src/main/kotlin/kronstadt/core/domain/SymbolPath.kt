package kronstadt.core.domain

/**
 * The address of one Symbol (ADR 0002): enclosing declaration names separated by `/`, e.g. `Outer/Nested/run`.
 *
 * A path may give only the trailing segments of a Symbol's chain; [anchored] (a leading `/`) pins it to the top level.
 * A segment may carry a parameter-type signature, `parse(String)`, compared to the Symbol's Signature
 * after whitespace is removed: no type resolution, no positional index. An extension's receiver is written before the
 * name, Kotlin-style (`String.shout`), and is compared the same way.
 *
 * A dotted [packageName] may come first (`com.example.a/Config`): it narrows a file-less lookup to that package and
 * anchors the path at the top level, like a fully qualified name. The prefix must contain a dot: a single-word first
 * segment is always a declaration name (`pkg/Name` is `Name` inside `pkg`), so Symbols in single-word packages are
 * told apart by passing the file.
 */
public data class SymbolPath(val segments: List<Segment>, val anchored: Boolean = false, val packageName: String? = null) {
    init {
        require(segments.isNotEmpty()) { "a Symbol Path has at least one segment" }
        require(packageName == null || anchored) { "a package prefix anchors the path at the top level" }
    }

    public data class Segment(val name: String, val signature: String? = null, val receiver: String? = null) {
        override fun toString(): String =
            (receiver?.let { "$it." } ?: "") + quote(name) + (signature?.let { "($it)" } ?: "")
    }

    /** The path in exactly the syntax [parse] accepts. */
    override fun toString(): String = when {
        packageName != null -> "$packageName/"
        anchored -> "/"
        else -> ""
    } + segments.joinToString("/")

    public companion object {
        private val plainName = Regex("[\\p{L}_][\\p{L}\\p{N}_]*")
        private val dottedPackage = Regex("${plainName.pattern}(\\.${plainName.pattern})+")

        /** Names that aren't plain identifiers are written in backticks, as in Kotlin source. */
        private fun quote(name: String) = if (plainName.matches(name)) name else "`$name`"

        /** Parses [text], or explains why it isn't a Symbol Path. */
        public fun parse(text: String): Result<SymbolPath> = runCatching {
            val trimmed = text.trim()
            require(trimmed.isNotEmpty()) { "Symbol Path is empty" }
            val parts = splitSegments(trimmed.removePrefix("/"))
            // A dotted first segment followed by others is a package: an extension (the other reading of a dot)
            // contains no addressable Symbols.
            val packageName = parts.first().trim().takeIf { parts.size > 1 && dottedPackage.matches(it) }
            val segments = (if (packageName == null) parts else parts.drop(1)).map(::parseSegment)
            SymbolPath(segments, anchored = trimmed.startsWith("/") || packageName != null, packageName = packageName)
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
            val signature = if (open < 0) null else segment.substring(open + 1, segment.length - 1)
            val dot = indexOfReceiverDot(rawName)
            val receiver = if (dot < 0) null else rawName.substring(0, dot).trim()
            val quotedName = if (dot < 0) rawName else rawName.substring(dot + 1).trim()
            val name = if (quotedName.length >= 2 && quotedName.startsWith("`") && quotedName.endsWith("`")) {
                quotedName.substring(1, quotedName.length - 1)
            } else {
                quotedName
            }
            require(receiver == null || receiver.isNotEmpty()) { "segment '$segment' has an empty receiver" }
            require(name.isNotBlank()) { "segment '$segment' has no name" }
            return Segment(name, signature, receiver)
        }

        /**
         * Index of the `(` that opens the signature: the top-level parenthesised group that ends the segment. Other
         * top-level groups must be followed by `.` or `?.`, as in a function-type receiver `((Int) -> Unit).twice`.
         * -1 when there is no signature. Anything inside backticks is ignored.
         */
        private fun indexOfSignature(segment: String): Int {
            var inBackticks = false
            var depth = 0
            var open = -1
            segment.forEachIndexed { i, c ->
                when {
                    c == '`' -> inBackticks = !inBackticks
                    inBackticks -> {}
                    c == '(' -> { if (depth == 0) open = i; depth++ }
                    c == ')' -> {
                        depth--
                        if (depth == 0) {
                            val rest = segment.substring(i + 1).trimStart()
                            if (rest.isEmpty()) return open
                            require(rest.startsWith(".") || rest.startsWith("?.")) { "nothing may follow the signature in '$segment'" }
                        }
                    }
                }
            }
            return -1
        }

        /**
         * Index of the `.` between an extension's receiver and its name: the last one outside backticks, type
         * arguments and parentheses, so `Map.Entry<K, V>.key` splits before `key`. -1 when there is no receiver.
         */
        private fun indexOfReceiverDot(name: String): Int {
            var inBackticks = false
            var depth = 0
            var dot = -1
            name.forEachIndexed { i, c ->
                when {
                    c == '`' -> inBackticks = !inBackticks
                    inBackticks -> {}
                    c == '<' || c == '(' -> depth++
                    c == ')' || (c == '>' && name.getOrNull(i - 1) != '-') -> depth--
                    c == '.' && depth == 0 -> dot = i
                }
            }
            return dot
        }
    }
}
