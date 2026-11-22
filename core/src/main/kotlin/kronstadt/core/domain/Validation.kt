package kronstadt.core.domain

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Checking the Shadow Workspace for errors an Edit introduced, before Write-back (ADR 0003). */
public object Validation {
    /** How long an Edit waits for diagnostics before it is rejected unwritten. */
    public val DEFAULT_TIMEOUT: Duration = 15.seconds

    /**
     * Whether [diagnostic] in [file] is one the build never reports, so Validation ignores it. Gradle applies explicit-API
     * mode to main sources only, but kotlin-lsp reports its diagnostics in every source set: those in a test source set
     * (`src/test`, or `src/<name>Test` such as `jvmTest` or `integrationTest`) are spurious (#48).
     */
    public fun isSpurious(file: Path, diagnostic: Diagnostic): Boolean {
        if (diagnostic.code !in EXPLICIT_API_CODES) return false
        // The innermost `src`, so a project kept under some `src/test` directory still has main sources.
        val sourceSet = file.zipWithNext().lastOrNull { (dir, _) -> dir.toString() == "src" }?.second?.toString() ?: return false
        return sourceSet == "test" || sourceSet.endsWith("Test")
    }

    /** [diagnostics] of [file] without the spurious ones (see [isSpurious]): those Validation compares. */
    public fun withoutSpurious(file: Path, diagnostics: List<Diagnostic>): List<Diagnostic> = diagnostics.filterNot { isSpurious(file, it) }

    /**
     * The [severity] diagnostics in [after] that weren't in [before]. Positions shift when code changes, so they are
     * compared by (code, message) only: a key is new as many times as its count rose. Which of a key's diagnostics
     * count as the new ones can't be known; those on [editedLines] (0-based ranges) are picked first, then the earliest.
     */
    public fun introduced(before: List<Diagnostic>, after: List<Diagnostic>, severity: Severity, editedLines: List<IntRange>): List<Diagnostic> {
        val previous = before.filter { it.severity == severity }.groupingBy(::key).eachCount()
        return after.filter { it.severity == severity }.groupBy(::key).flatMap { (key, found) ->
            val rise = found.size - (previous[key] ?: 0)
            found.sortedWith(compareBy({ diagnostic -> editedLines.none { diagnostic.range.start.line in it } }, { it.range.start })).take(maxOf(rise, 0))
        }.sortedBy { it.range.start }
    }

    private fun key(diagnostic: Diagnostic) = diagnostic.code to diagnostic.message

    private val EXPLICIT_API_CODES = setOf("NO_EXPLICIT_VISIBILITY_IN_API_MODE", "NO_EXPLICIT_RETURN_TYPE_IN_API_MODE")
}
