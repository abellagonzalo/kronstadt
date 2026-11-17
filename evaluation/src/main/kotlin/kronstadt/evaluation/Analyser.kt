package kronstadt.evaluation

import java.nio.file.Path
import java.time.Instant

/**
 * Which sessions form the sample: those touching a `.kt` file, started on or after [since], whose working directory is
 * inside one of [include] (any, if empty) and none of [exclude] (e.g. Kronstadt's own repo).
 */
internal data class SampleFilter(
    val include: List<Path> = emptyList(),
    val exclude: List<Path> = emptyList(),
    val since: Instant? = null,
) {
    fun admits(session: Session): Boolean =
        (include.isEmpty() || include.any { session.cwd.startsWith(it) }) &&
            exclude.none { session.cwd.startsWith(it) } &&
            (since == null || session.start >= since) &&
            session.calls.any { it.touchesKotlin() }

    private fun ToolCall.touchesKotlin(): Boolean = when (name) {
        "Read", "Edit", "MultiEdit", "Write" -> Kotlin.isKtPath(string("file_path"))
        "Grep", "Glob" -> isBuiltinKotlinRead()
        "Bash" -> Kotlin.ktPaths(string("command").orEmpty()).isNotEmpty()
        else -> KronstadtTool.of(name) != null
    }
}

/** Reads Claude Code transcripts and judges v1 against its bar. */
internal class Analyser(private val builds: Builds) {
    fun analyse(projects: Path, filter: SampleFilter): Report = Report(
        Transcripts.load(projects).filter(filter::admits).map { session ->
            val commits = Corruptions.check(session, builds)
            val circumventions = Circumventions.find(session)
            SessionReport(
                sessionId = session.id,
                cwd = session.cwd,
                start = session.start,
                kronstadtReads = session.calls.count { KronstadtTool.of(it.name) in KronstadtTool.READS },
                builtinKotlinReads = session.calls.count { it.isBuiltinKotlinRead() },
                circumventions = circumventions,
                corruptions = commits.corruptions,
                uncheckedCommits = commits.unchecked,
                bashKotlinReads = Informational.bashKotlinReads(session),
                edits = Informational.edits(session),
                rejectedEdits = Informational.rejectedEdits(session),
                readinessTimeouts = Informational.readinessTimeouts(session),
                hookDenials = HookDenials.followUps(session, circumventions),
            )
        },
    )
}

/** A built-in read or search aimed at Kotlin: `Read` of a `.kt`, `Grep`/`Glob` on a `.kt` path, a `*.kt` glob or `type: kotlin`. */
internal fun ToolCall.isBuiltinKotlinRead(): Boolean = when (name) {
    "Read" -> Kotlin.isKtPath(string("file_path"))
    "Grep" -> Kotlin.isKtPath(string("path")) || Kotlin.isKtGlob(string("glob")) || string("type") == "kotlin"
    "Glob" -> Kotlin.isKtGlob(string("pattern")) || Kotlin.isKtPath(string("path"))
    else -> false
}

/** Kronstadt's tools, whether the server was added as `kronstadt` or bundled in a plugin (`mcp__plugin_<p>_kronstadt__`). */
internal object KronstadtTool {
    val READS = setOf("kt_read_structure", "kt_read_symbol", "kt_find_symbol", "kt_find_usages", "kt_get_definition")
    val WRITES = setOf("kt_edit", "kt_rename_symbol")
    private val name = Regex("""^mcp__(?:.*_)?kronstadt__(kt_\w+)$""")

    /** The bare tool name (`kt_edit`) of a Kronstadt tool call, `null` for any other tool. */
    fun of(toolName: String): String? = name.find(toolName)?.groupValues?.get(1)
}
