package kronstadt.core.domain

/**
 * How a file indents and ends its lines, so code an agent sends can be laid out like the rest of the file (no
 * formatter runs). [indentUnit] is one level of indentation (a tab or some spaces).
 */
public data class FileLayout(val indentUnit: String, val lineSeparator: String) {
    /**
     * Lays out [code] for a target whose first line starts with [prefix]: strips the code's common leading indent,
     * converts its indent levels to [indentUnit], prefixes every non-blank line and joins lines with [lineSeparator].
     * Blank lines become empty; trailing blank lines are dropped. Leftover spaces that are not a whole level (such
     * as the space before a KDoc `*`) are kept as they are. Lines inside a multi-line raw string are data, so they
     * are kept exactly as sent (as is the end of the line it opens on) and play no part in the common indent or the
     * code's indent unit.
     */
    public fun reindent(code: String, prefix: String): String {
        val source = SourceLines.of(code)
        val raw = source.rawString
        val lines = code.lines().mapIndexed { i, line -> if (i in raw || i + 1 in raw) line else line.trimEnd() }.dropLastWhile { it.isEmpty() }
        val indented = lines.withIndex().filter { (i, line) -> i !in raw && line.isNotEmpty() }
        val common = indented.minOfOrNull { indentOf(it.value).length } ?: 0
        val codeUnit = unitOf(indented.filter { source.isCode(it.index) }.map { it.value.substring(common) }) ?: indentUnit
        return lines.withIndex().joinToString(lineSeparator) { (i, line) ->
            if (i in raw || line.isEmpty()) return@joinToString line
            val stripped = line.substring(common)
            val indent = indentOf(stripped)
            val levels = levelsOf(indent, codeUnit)
            val rest = indent.substring(levels * codeUnit.length).replace("\t", codeUnit)
            prefix + indentUnit.repeat(levels) + rest + stripped.substring(indent.length)
        }
    }

    public companion object {
        private const val DEFAULT_UNIT = "    "

        /** Reads the layout from [document]: its first line separator, and its indent unit (four spaces if it has none). */
        public fun of(document: TextDocument): FileLayout {
            val text = document.text
            val separator = Regex("\r\n|\n|\r").find(text)?.value ?: "\n"
            val source = SourceLines.of(text)
            val lines = text.lines().filterIndexed { i, _ -> source.isCode(i) }
            return FileLayout(unitOf(lines) ?: DEFAULT_UNIT, separator)
        }

        /** A tab if most indented lines start with one; otherwise the smallest indent of two or more spaces. */
        private fun unitOf(lines: List<String>): String? {
            val indents = lines.filter { it.isNotBlank() }.map(::indentOf).filter { it.isNotEmpty() }
            if (indents.isEmpty()) return null
            if (indents.count { it.startsWith('\t') } * 2 > indents.size) return "\t"
            val smallest = indents.map { it.takeWhile { c -> c == ' ' }.length }.filter { it >= 2 }.minOrNull() ?: return null
            return " ".repeat(smallest)
        }

        /** The spaces and tabs [line] starts with. */
        public fun indentOf(line: String): String = line.takeWhile { it == ' ' || it == '\t' }

        /** Whether [column] (0-based) of [line] is inside its indent: after some of its leading spaces and tabs, not all of them. */
        public fun isInsideIndent(line: String, column: Int): Boolean = column in 1 until indentOf(line).length

        /** Whole [unit]s at the start of [indent]. */
        private fun levelsOf(indent: String, unit: String): Int {
            var levels = 0
            while (indent.startsWith(unit.repeat(levels + 1))) levels++
            return levels
        }
    }
}
