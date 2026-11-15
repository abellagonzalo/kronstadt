package kronstadt.core.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Checking the Shadow Workspace for errors an Edit introduced, before Write-back (ADR 0003). */
public object Validation {
    /** How long an Edit waits for diagnostics before it is rejected unwritten. */
    public val DEFAULT_TIMEOUT: Duration = 15.seconds

    /**
     * The [severity] diagnostics in [after] that weren't in [before]. Positions shift when code changes, so they are
     * compared by (code, message) only: a key is new as many times as its count rose. Which of a key's diagnostics
     * count as the new ones can't be known; those on [editedLines] (0-based) are picked first, then the earliest.
     */
    public fun introduced(before: List<Diagnostic>, after: List<Diagnostic>, severity: Severity, editedLines: IntRange): List<Diagnostic> {
        val previous = before.filter { it.severity == severity }.groupingBy(::key).eachCount()
        return after.filter { it.severity == severity }.groupBy(::key).flatMap { (key, found) ->
            val rise = found.size - (previous[key] ?: 0)
            found.sortedWith(compareBy({ it.range.start.line !in editedLines }, { it.range.start })).take(maxOf(rise, 0))
        }.sortedBy { it.range.start }
    }

    private fun key(diagnostic: Diagnostic) = diagnostic.code to diagnostic.message
}
