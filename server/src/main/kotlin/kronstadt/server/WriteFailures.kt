package kronstadt.server

import kronstadt.core.application.Problem
import kronstadt.core.application.ValidationFailure
import kronstadt.core.application.WriteBackFailure

/**
 * What kt_edit and kt_rename_symbol tell the agent when Validation lets nothing be written: one wording for both.
 * [change] names what was rejected as the message opens it, e.g. `Edit` or `Rename`. A rejected Edit kept as the
 * Rejected Edit [id] is offered to amend.
 */
internal fun describe(failure: ValidationFailure, change: String, id: String? = null): String = when (failure) {
    is ValidationFailure.Rejected -> buildString {
        val count = failure.newErrors.size
        append(change).append(" rejected")
        if (id != null) append(" (id: ").append(id).append(')')
        append(": $count new error${if (count == 1) "" else "s"}; nothing written.\n")
        failure.newErrors.forEach { append(problem(it, withFile = true)) }
        append(
            if (id == null) {
                "Fix what causes them and retry."
            } else {
                "Fix with kt_edit amend: \"$id\" and only the fixing Operations, or resend the whole batch."
            },
        )
    }
    ValidationFailure.TimedOut -> "validation timed out; nothing written"
}

/** What kt_edit and kt_rename_symbol tell the agent when Write-back didn't write every file: one wording for both. */
internal fun describe(failure: WriteBackFailure): String = when (failure) {
    is WriteBackFailure.ChangedOnDisk -> "${failure.file} changed on disk since read; re-read and retry"
    is WriteBackFailure.PartiallyWritten ->
        "Write-back failed part-way: writing ${failure.failed} failed (${failure.reason}). " +
            "Already written, not rolled back: ${failure.written.joinToString()}. " +
            "Nothing else was written; re-read these files before retrying."
    is WriteBackFailure.TargetExists ->
        "${failure.file} would be renamed to ${failure.target}, which already exists; nothing written. " +
            "Pick another name, or move that file first."
}

/** One diagnostic as the agent sees it, on two lines: where it is and what it says, then the offending source line. */
internal fun problem(problem: Problem, withFile: Boolean) = buildString {
    append("  ")
    if (withFile) append(problem.file).append(':')
    append('L').append(problem.line)
    append(" in ").append(problem.symbol ?: "(file level)")
    append(": ").append(problem.message).append('\n')
    append("      ").append(problem.source).append('\n')
}
