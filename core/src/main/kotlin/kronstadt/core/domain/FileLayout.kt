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
     * as the space before a KDoc `*`) are kept as they are.
     */
    public fun reindent(code: String, prefix: String): String {
        val lines = code.lines().map { it.trimEnd() }.dropLastWhile { it.isEmpty() }
        val nonBlank = lines.filter { it.isNotEmpty() }
        val common = nonBlank.minOfOrNull { indentOf(it).length } ?: 0
        val stripped = lines.map { if (it.isEmpty()) it else it.substring(common) }
        val codeUnit = unitOf(stripped) ?: indentUnit
        return stripped.joinToString(lineSeparator) { line ->
            if (line.isEmpty()) return@joinToString line
            val indent = indentOf(line)
            val levels = levelsOf(indent, codeUnit)
            val rest = indent.substring(levels * codeUnit.length).replace("\t", codeUnit)
            prefix + indentUnit.repeat(levels) + rest + line.substring(indent.length)
        }
    }

    public companion object {
        private const val DEFAULT_UNIT = "    "

        /** Reads the layout from [document]: its first line separator, and its indent unit (four spaces if it has none). */
        public fun of(document: TextDocument): FileLayout {
            val text = document.text
            val separator = Regex("\r\n|\n|\r").find(text)?.value ?: "\n"
            return FileLayout(unitOf(text.lines()) ?: DEFAULT_UNIT, separator)
        }

        /** A tab if most indented lines start with one; otherwise the smallest indent of two or more spaces. */
        private fun unitOf(lines: List<String>): String? {
            val indents = lines.filter { it.isNotBlank() }.map(::indentOf).filter { it.isNotEmpty() }
            if (indents.isEmpty()) return null
            if (indents.count { it.startsWith('\t') } * 2 > indents.size) return "\t"
            val smallest = indents.map { it.takeWhile { c -> c == ' ' }.length }.filter { it >= 2 }.minOrNull() ?: return null
            return " ".repeat(smallest)
        }

        private fun indentOf(line: String) = line.takeWhile { it == ' ' || it == '\t' }

        /** Whole [unit]s at the start of [indent]. */
        private fun levelsOf(indent: String, unit: String): Int {
            var levels = 0
            while (indent.startsWith(unit.repeat(levels + 1))) levels++
            return levels
        }
    }
}
