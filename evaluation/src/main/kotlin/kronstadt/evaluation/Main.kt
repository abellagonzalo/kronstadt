package kronstadt.evaluation

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.system.exitProcess

private const val USAGE = """Usage: evaluation [--projects <dir>] [--include <dir>]... [--exclude <dir>]... [--since <date>] [--no-compile]

Judges Kronstadt v1 on Claude Code transcripts: 0 corrupted files, 0 circumventions, at least 80% of Kotlin reads
and searches through Kronstadt. Prints each sample session, then the totals; exits 0 if the sample is complete and meets the bar, else 1.

  --projects <dir>  Claude Code's transcripts (default: ~/.claude/projects)
  --include <dir>   only sessions working inside this directory (repeatable; default: all)
  --exclude <dir>   skip sessions working inside this directory, e.g. Kronstadt's own repo (repeatable)
  --since <date>    only sessions started on or after this date (YYYY-MM-DD, local time) or instant
  --no-compile      don't compile session commits; corrupted files are then not checked"""

/** Dev-only: `./gradlew :evaluation:run --args="--include $HOME/github/traderepublic --exclude $HOME/github/abellagonzalo/kronstadt --since 2026-12-01"`. */
public fun main(args: Array<String>) {
    var projects = Path.of(System.getProperty("user.home"), ".claude", "projects")
    val include = mutableListOf<Path>()
    val exclude = mutableListOf<Path>()
    var since: Instant? = null
    var compile = true
    val queue = ArrayDeque(args.toList())
    fun value(flag: String) = queue.removeFirstOrNull() ?: usage("$flag needs a value")
    while (queue.isNotEmpty()) {
        when (val flag = queue.removeFirst()) {
            "--projects" -> projects = Path.of(value(flag))
            "--include" -> include.add(Path.of(value(flag)).toAbsolutePath().normalize())
            "--exclude" -> exclude.add(Path.of(value(flag)).toAbsolutePath().normalize())
            "--since" -> since = parseSince(value(flag))
            "--no-compile" -> compile = false
            "-h", "--help" -> { println(USAGE); return }
            else -> usage("unknown argument: $flag")
        }
    }
    val builds = if (compile) GitGradleBuilds() else Builds { _, _ -> Breakage.Unavailable("--no-compile") }
    val report = Analyser(builds).analyse(projects, SampleFilter(include, exclude, since))
    print(report.render())
    exitProcess(if (report.passes && report.sampleComplete) 0 else 1)
}

private fun parseSince(text: String): Instant =
    runCatching { LocalDate.parse(text).atStartOfDay(ZoneId.systemDefault()).toInstant() }.getOrNull()
        ?: runCatching { Instant.parse(text) }.getOrNull()
        ?: usage("--since takes a date (YYYY-MM-DD) or an instant, got '$text'")

private fun usage(problem: String): Nothing {
    System.err.println("$problem\n\n$USAGE")
    exitProcess(2)
}
