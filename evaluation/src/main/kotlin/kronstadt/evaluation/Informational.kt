package kronstadt.evaluation

/** Metrics that inform tuning the steering but are not pass criteria. */
internal object Informational {
    /** Bash `grep`/`rg`, `cat` and `sed -n` commands aimed at Kotlin, counted per command (`grep` covers `egrep` and `rg`). */
    fun bashKotlinReads(session: Session): Map<String, Int> = session.calls
        .filter { it.name == "Bash" }
        .flatMap { Shell.segments(it.string("command").orEmpty()) }
        .mapNotNull { segment -> readKind(segment)?.takeIf { aimsAtKotlin(segment) } }
        .groupingBy { it }.eachCount()

    private fun readKind(segment: Shell.Segment): String? = when (segment.program) {
        "grep", "egrep", "fgrep", "rg" -> "grep"
        "cat" -> "cat"
        "sed" -> "sed -n".takeIf { segment.args.any { it == "--quiet" || Regex("""^-[a-zA-Z]*n""").matches(it) } && !segment.editsInPlace }
        else -> null
    }

    /** Names a `.kt` path or glob (`--include=*.kt`, `-g '*.kt'`) or ripgrep's `kotlin` type. */
    private fun aimsAtKotlin(segment: Shell.Segment): Boolean {
        val args = segment.args.drop(1)
        return args.any { Kotlin.isKtGlob(it) } ||
            args.zipWithNext().any { (flag, value) -> flag in setOf("-t", "--type") && value == "kotlin" } ||
            args.any { it == "--type=kotlin" || it == "-tkotlin" }
    }

    /** `kt_edit` calls that reached Validation: written, or rejected for new errors (not readiness, resolution or disk failures). */
    fun edits(session: Session): Int = ktEditResults(session).count { it.startsWith("Edited ") || it.startsWith(REJECTED) }

    /** `kt_edit` calls whose Validation rejected the edit. */
    fun rejectedEdits(session: Session): Int = ktEditResults(session).count { it.startsWith(REJECTED) }

    private const val REJECTED = "Edit rejected:"

    private fun ktEditResults(session: Session) = session.calls.filter { KronstadtTool.of(it.name) == "kt_edit" }.map { it.result.orEmpty() }

    /** Kronstadt calls that gave up waiting for kotlin-lsp to finish importing and indexing. */
    fun readinessTimeouts(session: Session): Int =
        session.calls.count { KronstadtTool.of(it.name) != null && it.result.orEmpty().startsWith("kotlin-lsp still indexing") }
}
