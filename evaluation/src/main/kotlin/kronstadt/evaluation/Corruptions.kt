package kronstadt.evaluation

import java.nio.file.Path

/** A file a Kronstadt Write-back touched in the session, broken by a session commit whose parent compiled. */
internal data class Corruption(val commit: String, val file: Path)

/** The outcome of checking a session's commits for corruption. */
internal data class CommitChecks(val corruptions: List<Corruption>, val unchecked: List<UncheckedCommit>)

internal object Corruptions {
    /** The short or full hash `git commit` prints, e.g. `[main 1a2b3c4] message` or `[detached HEAD 1a2b3c4] message`. */
    private val committed = Regex("""^\[(?:[^\]\n]+ )?([0-9a-f]{7,40})] """, RegexOption.MULTILINE)

    private val written = Regex("""^Edited (.+\.kts?)$""", RegexOption.MULTILINE)

    fun check(session: Session, builds: Builds): CommitChecks {
        val writtenBack = writeBacks(session)
        val corruptions = mutableListOf<Corruption>()
        val unchecked = mutableListOf<UncheckedCommit>()
        for ((repo, commit) in commits(session)) {
            when (val breakage = builds.breakage(repo, commit)) {
                is Breakage.Breaks -> breakage.files.filter { it in writtenBack }.sorted().forEach { corruptions.add(Corruption(commit, it)) }
                is Breakage.Unavailable -> unchecked.add(UncheckedCommit(commit, breakage.reason))
                Breakage.Compiles, Breakage.NotKotlin, Breakage.ParentBroken -> Unit
            }
        }
        return CommitChecks(corruptions, unchecked)
    }

    /** Files a successful Kronstadt edit wrote: its result names each as `Edited <path>`, relative to the project root. */
    private fun writeBacks(session: Session): Set<Path> = session.calls
        .filter { KronstadtTool.of(it.name) in KronstadtTool.WRITES && !it.isError }
        .flatMap { call -> written.findAll(call.result.orEmpty()).map { session.cwd.resolve(it.groupValues[1].trim()).normalize() }.toList() }
        .toSet()

    /**
     * Commits the session made, each with the directory git ran in: any `git` command printing `[branch hash] subject`
     * (commit, cherry-pick, revert, merge...), whether or not the whole command then failed, e.g. on a later `git push`.
     */
    private fun commits(session: Session): List<Pair<Path, String>> = session.calls
        .filter { it.name == "Bash" }
        .flatMap { call ->
            val dir = Shell.located(call.string("command").orEmpty(), call.cwd).lastOrNull { (segment, _) -> segment.program == "git" }?.second
            if (dir == null) emptyList() else committed.findAll(call.result.orEmpty()).map { dir to it.groupValues[1] }.toList()
        }
        .distinct()
}
