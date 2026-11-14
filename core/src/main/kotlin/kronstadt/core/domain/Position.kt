package kronstadt.core.domain

/**
 * A zero-based position in a document, using LSP semantics:
 * [character] counts UTF-16 code units, which is exactly what a Kotlin [String] index counts.
 */
public data class Position(val line: Int, val character: Int) : Comparable<Position> {
    init {
        require(line >= 0) { "line must be >= 0, was $line" }
        require(character >= 0) { "character must be >= 0, was $character" }
    }

    override fun compareTo(other: Position): Int = compareValuesBy(this, other, Position::line, Position::character)
}

/** A half-open range `[start, end)` in a document. */
public data class TextRange(val start: Position, val end: Position) {
    init {
        require(start <= end) { "start $start must not be after end $end" }
    }

    public operator fun contains(position: Position): Boolean = position >= start && position < end
}
