package kronstadt.core.domain

/**
 * A Symbol's full source as whole lines (ADR 0004): its KDoc or comments above, annotations, modifiers, the
 * declaration and a comment at the end of its last line. [firstLine] and [lastLine] are zero-based and inclusive.
 */
public data class SymbolSpan(val firstLine: Int, val lastLine: Int, val text: String) {
    public sealed interface Computation {
        public data class Computed(val span: SymbolSpan) : Computation

        /** The text at the name's range isn't the name: the analysis doesn't describe this text. */
        public data object OutOfSync : Computation

        /** Something other than whitespace shares [line] (zero-based) with the Symbol, so whole lines would cut it. */
        public data class SharedLine(val line: Int) : Computation
    }

    public companion object {
        /**
         * The 0-based first and last lines a listing shows for [symbol]: its Symbol Span's, as `kt_read_symbol` reports
         * them, or its declaration's when it has no whole-line span (it shares a line with other code).
         */
        public fun lines(document: TextDocument, symbol: CodeSymbol): Pair<Int, Int> =
            when (val computation = of(document, symbol)) {
                is Computation.Computed -> computation.span.firstLine to computation.span.lastLine
                else -> symbol.range.start.line to symbol.range.end.line
            }

        /**
         * Trusts the language server's `range` as the span, with guards: the name must be where `selectionRange`
         * says, and only whitespace (or an end-of-line comment) may share the first and last lines.
         */
        public fun of(document: TextDocument, symbol: CodeSymbol): Computation {
            if (!nameMatches(document, symbol)) return Computation.OutOfSync
            val range = symbol.range
            val firstLine = range.start.line
            // A range ending at column 0 stops before that line.
            val lastLine = if (range.end.character == 0 && range.end.line > firstLine) range.end.line - 1 else range.end.line
            if (lastLine >= document.lineCount) return Computation.OutOfSync

            val before = document.slice(TextRange(Position(firstLine, 0), range.start))
            if (before.isNotBlank()) return Computation.SharedLine(firstLine)
            if (lastLine == range.end.line) {
                val after = document.slice(TextRange(range.end, Position(lastLine, Int.MAX_VALUE))).trim()
                if (after.isNotEmpty() && !isComment(after)) return Computation.SharedLine(lastLine)
            }
            return Computation.Computed(SymbolSpan(firstLine, lastLine, document.lines(firstLine, lastLine)))
        }

        /**
         * Some Symbols (constructors, accessors, unnamed companions) have no separate name range: the server reports
         * the whole declaration there, so there is no name to check.
         */
        private fun nameMatches(document: TextDocument, symbol: CodeSymbol): Boolean {
            if (symbol.selectionRange == symbol.range) return true
            if (symbol.selectionRange.end.line >= document.lineCount) return false
            val text = document.slice(symbol.selectionRange)
            return text == symbol.name || text == "`${symbol.name}`"
        }

        /** A `//` comment, or one block comment that closes only at the very end (no code between two comments). */
        private fun isComment(text: String) = text.startsWith("//") ||
            (text.startsWith("/*") && text.indexOf("*/", startIndex = 2) == text.length - 2)
    }
}
