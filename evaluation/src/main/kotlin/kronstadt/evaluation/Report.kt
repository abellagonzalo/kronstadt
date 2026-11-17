package kronstadt.evaluation

import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** The bar v1 is judged on: at least this share of Kotlin reads and searches through Kronstadt. */
internal const val SHARE_BAR = 0.8

/** The sample is complete once it holds this many sessions and spans [SAMPLE_DAYS], whichever comes later. */
internal const val SAMPLE_SESSIONS = 30

/** Two working weeks, as calendar days. */
internal const val SAMPLE_DAYS = 14L

/** A session commit that could not be compiled, and why. */
internal data class UncheckedCommit(val commit: String, val reason: String)

internal data class SessionReport(
    val sessionId: String,
    val cwd: Path,
    val start: Instant,
    val kronstadtReads: Int,
    val builtinKotlinReads: Int,
    val circumventions: List<Circumvention>,
    val corruptions: List<Corruption>,
    val uncheckedCommits: List<UncheckedCommit>,
    /** Informational: Bash `grep`/`cat`/`sed -n` on Kotlin, per command. */
    val bashKotlinReads: Map<String, Int>,
    /** Informational: `kt_edit` calls that reached Validation, and those it rejected. */
    val edits: Int,
    val rejectedEdits: Int,
    val readinessTimeouts: Int,
    /** Informational: hook denials, counted by what the agent did next. */
    val hookDenials: Map<String, Int>,
) {
    val readShare: Double? get() = ratio(kronstadtReads, kronstadtReads + builtinKotlinReads)
}

/** The whole sample's figures, summed over its sessions. */
internal class Totals(sessions: List<SessionReport>) {
    val sessions: Int = sessions.size
    val kronstadtReads: Int = sessions.sumOf { it.kronstadtReads }
    val builtinKotlinReads: Int = sessions.sumOf { it.builtinKotlinReads }
    val circumventions: Int = sessions.sumOf { it.circumventions.size }
    val corruptedFiles: Int = sessions.sumOf { it.corruptions.size }
    val uncheckedCommits: Int = sessions.sumOf { it.uncheckedCommits.size }
    val bashKotlinReads: Map<String, Int> = sum(sessions.map { it.bashKotlinReads })
    val hookDenials: Map<String, Int> = sum(sessions.map { it.hookDenials })
    val edits: Int = sessions.sumOf { it.edits }
    val rejectedEdits: Int = sessions.sumOf { it.rejectedEdits }
    val readinessTimeouts: Int = sessions.sumOf { it.readinessTimeouts }

    /** Rejected edits ÷ edits that reached Validation; `null` with none. */
    val rejectionRate: Double? get() = ratio(rejectedEdits, edits)

    /** Kronstadt read/search calls ÷ (those + built-in reads and searches aimed at Kotlin); `null` with none of either. */
    val readShare: Double? get() = ratio(kronstadtReads, kronstadtReads + builtinKotlinReads)

    private fun sum(counts: List<Map<String, Int>>): Map<String, Int> =
        counts.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
}

internal data class Report(val sessions: List<SessionReport>) {
    val total: Totals = Totals(sessions)

    /** Whole days between the first and the last session's start. */
    val days: Long = if (sessions.isEmpty()) 0 else Duration.between(sessions.minOf { it.start }, sessions.maxOf { it.start }).toDays()

    /** Enough sessions over enough time to judge v1. */
    val sampleComplete: Boolean get() = sessions.size >= SAMPLE_SESSIONS && days >= SAMPLE_DAYS

    val meetsShareBar: Boolean get() = (total.readShare ?: 0.0) >= SHARE_BAR

    /** 0 corrupted files counts only if every session commit was compiled. */
    val meetsCorruptionBar: Boolean get() = total.corruptedFiles == 0 && total.uncheckedCommits == 0

    /** v1 meets its bar: 0 corrupted files, 0 circumventions, at least 80% of Kotlin reads and searches through Kronstadt. */
    val passes: Boolean get() = meetsCorruptionBar && total.circumventions == 0 && meetsShareBar
}

/** [part] ÷ [whole], `null` when [whole] is 0. */
internal fun ratio(part: Int, whole: Int): Double? = whole.takeIf { it > 0 }?.let { part.toDouble() / it }
