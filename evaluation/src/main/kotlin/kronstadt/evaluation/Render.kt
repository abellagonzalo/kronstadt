package kronstadt.evaluation

import java.util.Locale

/** The report as plain text: one block per session, then the totals against the bar and the verdict. */
internal fun Report.render(): String = buildString {
    for (session in sessions) {
        append("Session ${session.sessionId}  ${session.cwd}  ${session.start}\n")
        append("  reads/searches: ${session.kronstadtReads} Kronstadt, ${session.builtinKotlinReads} built-in")
        session.readShare?.let { append(" (${percent(it)})") }
        append('\n')
        session.circumventions.forEach { append("  circumvention: ${it.file}  ${it.how}\n") }
        session.corruptions.forEach { append("  corrupted: ${it.file} in ${it.commit}\n") }
        session.uncheckedCommits.forEach { append("  unchecked commit: ${it.commit} (${it.reason})\n") }
        append("  kt_edit: ${session.edits}, ${session.rejectedEdits} rejected by Validation; readiness timeouts: ${session.readinessTimeouts}\n")
        append("  Bash reads of .kt: ${bashReads(session.bashKotlinReads)}\n")
        if (session.hookDenials.isNotEmpty()) append("  hook denials, then: ${counts(session.hookDenials)}\n")
        append('\n')
    }
    val total = total
    append("Total over ${plural(total.sessions, "session")}\n")
    append("  sample: ${plural(total.sessions, "session")} over ${plural(days.toInt(), "day")} ")
    append("(complete at $SAMPLE_SESSIONS sessions and $SAMPLE_DAYS days)  ${if (sampleComplete) "COMPLETE" else "INCOMPLETE"}\n")
    append("  read/search share: ${total.kronstadtReads} of ${total.kronstadtReads + total.builtinKotlinReads} = ")
    append(total.readShare?.let(::percent) ?: "n/a").append(" (bar: at least ${percent(SHARE_BAR, 0)})  ${verdict(meetsShareBar)}\n")
    append("  circumventions: ${total.circumventions} (bar: 0)  ${verdict(total.circumventions == 0)}\n")
    append("  corrupted files: ${total.corruptedFiles}")
    if (total.uncheckedCommits > 0) append(", ${plural(total.uncheckedCommits, "commit")} unchecked")
    append(" (bar: 0, every commit checked)  ${verdict(meetsCorruptionBar)}\n")
    append("  kt_edit: ${total.edits}, ${total.rejectedEdits} rejected by Validation")
    total.rejectionRate?.let { append(" (${percent(it)})") }
    append("; readiness timeouts: ${total.readinessTimeouts}\n")
    append("  Bash reads of .kt: ${bashReads(total.bashKotlinReads)}\n")
    if (total.hookDenials.isNotEmpty()) append("  hook denials, then: ${counts(total.hookDenials)}\n")
    append(verdict(passes)).append('\n')
}

private fun plural(count: Int, noun: String) = "$count $noun${if (count == 1) "" else "s"}"

private fun percent(value: Double, decimals: Int = 1) = String.format(Locale.ROOT, "%.${decimals}f%%", value * 100)

private fun verdict(ok: Boolean) = if (ok) "PASS" else "FAIL"

private fun bashReads(counts: Map<String, Int>) = listOf("sed -n", "grep", "cat").joinToString(", ") { "$it ${counts[it] ?: 0}" }

private fun counts(counts: Map<String, Int>) = counts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.key} ${it.value}" }
