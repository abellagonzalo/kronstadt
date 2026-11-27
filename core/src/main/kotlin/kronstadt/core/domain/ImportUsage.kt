package kronstadt.core.domain

/**
 * What the code intelligence found of some imports of a file's text (ADR 0004): those that are Unused Imports, and
 * those it couldn't decide about, e.g. without an answer in time. Every other import it was asked about is used.
 */
public data class ImportUsage(val unused: Set<ImportDirective>, val undecided: Set<ImportDirective> = emptySet()) {
    init {
        require(unused.none { it in undecided }) { "an import is either unused or undecided: ${unused.intersect(undecided)}" }
    }

    /** Those of [among] this shows used: neither unused nor undecided. */
    public fun usedAmong(among: Set<ImportDirective>): Set<ImportDirective> = among - unused - undecided

    public companion object {
        /** No answer about any of [among]. */
        public fun undecided(among: Set<ImportDirective>): ImportUsage = ImportUsage(emptySet(), among)
    }
}
