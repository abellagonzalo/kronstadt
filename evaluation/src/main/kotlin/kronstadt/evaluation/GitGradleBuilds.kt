package kronstadt.evaluation

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * [Builds] with git and the repo's own Gradle wrapper: checks the commit and then its parent out into throwaway
 * worktrees and runs `compileKotlin compileTestKotlin` on the modules owning the commit's `.kt` files. A module is the
 * nearest directory with a `build.gradle(.kts)`, its Gradle path taken from its location (`app/core` is `:app:core`).
 */
internal class GitGradleBuilds(private val timeout: Duration = 20.minutes) : Builds {
    /** A Kotlin compiler error line: `e: file:///abs/path/File.kt:12:5 message`. */
    private val compilerError = Regex("""^e: (?:file://)?(/[^\s:]+\.kt):\d+""", RegexOption.MULTILINE)

    override fun breakage(repo: Path, commit: String): Breakage {
        if (!repo.isDirectory()) return Breakage.Unavailable("$repo no longer exists")
        val root = run(repo, "git", "rev-parse", "--show-toplevel").takeIf { it.ok }?.out?.trim()?.let(Path::of)
            ?: return Breakage.Unavailable("$repo is not a git repository")
        if (!run(root, "git", "cat-file", "-e", "$commit^{commit}").ok) return Breakage.Unavailable("commit $commit not found in $root")

        val files = run(root, "git", "diff-tree", "--no-commit-id", "--name-only", "-r", "--root", commit).out.lines().filter(Kotlin::isKtPath)
        if (files.isEmpty()) return Breakage.NotKotlin
        val parent = run(root, "git", "rev-parse", "--verify", "--quiet", "$commit^").takeIf { it.ok }?.out?.trim()
            ?: return Breakage.Unavailable("commit $commit has no parent")

        val broken = when (val commitBuild = compile(root, commit, files)) {
            Compile.Ok -> return Breakage.Compiles
            is Compile.Failed -> commitBuild.reason?.let { return Breakage.Unavailable(it) } ?: commitBuild.files
        }
        return when (val parentBuild = compile(root, parent, files)) {
            Compile.Ok -> Breakage.Breaks(broken.map { root.resolve(it).normalize() }.toSet())
            is Compile.Failed -> parentBuild.reason?.let(Breakage::Unavailable) ?: Breakage.ParentBroken
        }
    }

    private sealed interface Compile {
        data object Ok : Compile

        /** Failed with errors in [files] (relative to the repo root), or could not run at all for [reason]. */
        data class Failed(val files: Set<String>, val reason: String? = null) : Compile
    }

    /** Compiles [revision] in a throwaway worktree; a build failing without compiler errors (e.g. dependencies) can't judge. */
    private fun compile(root: Path, revision: String, files: List<String>): Compile {
        val tree = Files.createTempDirectory("kronstadt-evaluation").resolve("tree")
        if (!run(root, "git", "worktree", "add", "--detach", "--quiet", tree.toString(), revision).ok) {
            return Compile.Failed(emptySet(), "could not check out $revision")
        }
        try {
            if (!tree.resolve("gradlew").exists()) return Compile.Failed(emptySet(), "$revision has no gradlew")
            val tasks = files.map { module(tree, it) }.distinct().sorted().flatMap { listOf("$it:compileKotlin", "$it:compileTestKotlin") }
            val started = System.nanoTime()
            val build = run(tree, listOf("./gradlew") + tasks + "--quiet", timeout)
            val outcome = when {
                build.timedOut -> Compile.Failed(emptySet(), "compiling $revision timed out")
                build.ok -> Compile.Ok
                else -> {
                    val realTree = tree.toRealPath()
                    val broken = compilerError.findAll(build.out).map { Path.of(it.groupValues[1]) }
                        .mapNotNull { path -> listOf(realTree, tree).firstOrNull { path.startsWith(it) }?.relativize(path)?.toString() }
                        .toSet()
                    if (broken.isNotEmpty()) {
                        Compile.Failed(broken)
                    } else {
                        val tail = build.out.lines().filter { it.isNotBlank() }.takeLast(5).joinToString(" / ")
                        Compile.Failed(emptySet(), "build of $revision failed without compiler errors: $tail")
                    }
                }
            }
            System.err.println("compiled $revision in $root (${tasks.joinToString(" ")}): $outcome in ${(System.nanoTime() - started) / 1_000_000_000} s")
            return outcome
        } finally {
            run(root, "git", "worktree", "remove", "--force", tree.toString())
            tree.parent.toFile().deleteRecursively()
        }
    }

    /** The Gradle path (`:app:core`, or empty for the root project) of the module owning [file]. */
    private fun module(tree: Path, file: String): String {
        var dir: Path? = tree.resolve(file).parent
        while (dir != null && dir.startsWith(tree) && dir != tree) {
            if (dir.resolve("build.gradle.kts").exists() || dir.resolve("build.gradle").exists()) {
                return tree.relativize(dir).joinToString("") { ":$it" }
            }
            dir = dir.parent
        }
        return ""
    }

    private class Run(val ok: Boolean, val out: String, val timedOut: Boolean = false)

    private fun run(dir: Path, vararg command: String) = run(dir, command.toList(), 1.minutes)

    private fun run(dir: Path, command: List<String>, timeout: Duration): Run {
        val log = Files.createTempFile("kronstadt-evaluation", ".log")
        try {
            val process = ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start()
            if (!process.waitFor(timeout.inWholeSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                return Run(ok = false, out = "", timedOut = true)
            }
            return Run(process.exitValue() == 0, log.toFile().readText())
        } finally {
            Files.deleteIfExists(log)
        }
    }
}
