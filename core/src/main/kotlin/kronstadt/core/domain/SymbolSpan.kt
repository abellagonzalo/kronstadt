package kronstadt.core.domain

/**
 * A Symbol's full source as whole lines (ADR 0004): its KDoc or comments above, annotations, modifiers, the
 * declaration, a list separator (`,`) right after it and a comment at the end of its last line. [firstLine] and
 * [lastLine] are zero-based and inclusive.
 */
public data class SymbolSpan(val firstLine: Int, val lastLine: Int, val text: String) {
    /** The span's lines replaced by [newLines], laid out for the file. */
    public fun replacedBy(newLines: List<String>): LineReplacement = LineReplacement(firstLine..lastLine, newLines)

    /**
     * [code] added as a sibling above the span, from its first non-empty line, with one blank line between them. [code]'s
     * last line is non-blank, so the blank line is this one's alone.
     */
    public fun siblingBefore(code: List<String>): LineReplacement =
        LineReplacement(firstLine until firstLine, code.dropWhile { it.isEmpty() } + "")

    /** [code] added as a sibling below the span, from its first non-empty line, with one blank line between them. */
    public fun siblingAfter(code: List<String>): LineReplacement =
        LineReplacement(lastLine + 1 until lastLine + 1, listOf("") + code.dropWhile { it.isEmpty() })

    /**
     * The span removed from [document] with one blank line next to it: the one below if there is one, else the one above.
     * The empty line after a final newline is no blank line between declarations, so it is never taken.
     */
    public fun deletion(document: TextDocument): LineReplacement {
        val afterSpan = lastLine + 1
        val blankAfter = afterSpan < document.lineCount - 1 && document.line(afterSpan).isBlank()
        val blankBefore = firstLine > 0 && document.line(firstLine - 1).isBlank()
        val lines = when {
            blankAfter -> firstLine..afterSpan
            blankBefore -> (firstLine - 1)..lastLine
            else -> firstLine..lastLine
        }
        return LineReplacement(lines, emptyList())
    }

    public sealed interface Computation {
        public data class Computed(val span: SymbolSpan) : Computation

        /** The text at the name's range isn't the name: the analysis doesn't describe this text. */
        public data object OutOfSync : Computation

        /** Something other than whitespace shares [line] (zero-based) with the Symbol, so whole lines would cut it. */
        public data class SharedLine(val line: Int) : Computation
    }

    public companion object {
        /**
         * The 0-based first and last lines a listing shows for [symbol]: its Symbol Span's, as reading the Symbol reports
         * them, or its declaration's when it has no whole-line span (it shares a line with other code).
         */
        public fun lines(document: TextDocument, symbol: CodeSymbol): Pair<Int, Int> =
            when (val computation = of(document, symbol)) {
                is Computation.Computed -> computation.span.firstLine to computation.span.lastLine
                else -> symbol.range.start.line to symbol.range.end.line
            }

        /**
         * Trusts the language server's `range` as the span, with guards: the name must be where `selectionRange`
         * says, and only whitespace (or an end-of-line comment) may share the first and last lines. The last line may
         * also hold a list separator (`,`) right after the range, before any such comment.
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
                // A lone `,` right after a declaration can only be a list separator (#68): it belongs to the span.
                val after = document.slice(TextRange(range.end, Position(lastLine, Int.MAX_VALUE))).trim()
                    .removePrefix(",").trimStart()
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

/**
 * Whole [lines] of a [TextDocument] (zero-based, inclusive; empty to insert before `lines.first`) and the [newLines] that
 * take their place, as [TextDocument.replaceLines] takes them: how an Operation lays out its change around a Symbol Span.
 */
public data class LineReplacement(val lines: IntRange, val newLines: List<String>)
