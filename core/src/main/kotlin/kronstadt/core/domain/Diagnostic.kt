package kronstadt.core.domain

public enum class Severity { ERROR, WARNING, INFORMATION, HINT }

/** A compiler or inspection finding in one document, as the code intelligence reports it. */
public data class Diagnostic(
    val range: TextRange,
    val severity: Severity,
    val message: String,
    /** The finding's identifier (e.g. `UNRESOLVED_REFERENCE`), if the code intelligence gives one. */
    val code: String? = null,
)
