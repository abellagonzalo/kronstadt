package kronstadt.core.application

/** Why Validation let nothing be written, for an Edit and a rename alike. */
public sealed interface ValidationFailure {
    /** Validation found [newErrors] the change introduced; nothing was written. */
    public data class Rejected(val newErrors: List<Problem>) : ValidationFailure

    /** Validation's diagnostics took longer than its timeout; nothing was written. */
    public data object TimedOut : ValidationFailure
}

/** Why Write-back didn't write every changed file, for an Edit and a rename alike. */
public sealed interface WriteBackFailure {
    /** [file] changed on disk since it was read; nothing was written. */
    public data class ChangedOnDisk(val file: String) : WriteBackFailure

    /**
     * Write-back failed part-way: [written] are on disk, writing [failed] failed with [reason], and the other files
     * weren't written. Nothing is rolled back (ADR 0003, Consequences).
     */
    public data class PartiallyWritten(val written: List<String>, val failed: String, val reason: String) : WriteBackFailure

    /** [file] would be moved onto [target], which exists; nothing was written. Only a rename moves a file. */
    public data class TargetExists(val file: String, val target: String) : WriteBackFailure
}
