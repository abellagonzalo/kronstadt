package kronstadt.lsp

/**
 * Whether kotlin-lsp's index may become the Index Seed ([complete]: the import FINISHED and no progress is open; see
 * [ReadinessTracker.indexComplete]), with what keeps it from being so: the last [importPhase] and the [openProgress]
 * tokens with their titles, in the order they began.
 */
public data class IndexState(
    val complete: Boolean,
    val importPhase: String?,
    val openProgress: Map<String, String>,
) {
    /** `complete`, or the import phase and the open progress, e.g. `import FINISHED; open progress: Indexing [t1]`. */
    override fun toString(): String =
        if (complete) {
            "complete"
        } else {
            val open = openProgress.entries.joinToString { (token, title) -> "$title [$token]" }.ifEmpty { "none" }
            "import ${importPhase ?: "not reported"}; open progress: $open"
        }
}
