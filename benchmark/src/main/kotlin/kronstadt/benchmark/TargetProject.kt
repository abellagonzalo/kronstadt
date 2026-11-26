@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.benchmark

import kronstadt.workspace.IndexDirectories
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists

/**
 * The project the Benchmark Tasks are written for, at a pinned release. kotlinpoet: a public, multi-module Gradle
 * project that kotlin-lsp 271.2085.0 imports cleanly. (JetBrains Exposed was the first choice, but its Gradle 8.14
 * doesn't run on JDK 25, so kotlin-lsp can't import it.)
 */
internal data class TargetProject(val repository: String, val tag: String, val commit: String, val warmQuery: String) {
    val description: String get() = "${repository.removePrefix("https://github.com/").removeSuffix(".git")} $tag (${commit.take(8)})"

    companion object {
        val KOTLINPOET: TargetProject = TargetProject(
            repository = "https://github.com/square/kotlinpoet.git",
            tag = "2.4.0",
            commit = "97e504bbcece8100b653c5d7d7cd1bba0a55b0b3",
            // Any tool that needs the index waits for kotlin-lsp to finish importing and indexing.
            warmQuery = "FunSpec",
        )
    }
}

/**
 * Run worktrees of [target], made from one local [clone] at its pinned commit under [root], and deleted afterwards
 * together with the kotlin-lsp index Kronstadt kept for them. Worktrees, not clones: Index Seeds are shared per git
 * common directory, so separate clones would each index cold.
 */
internal class GitRunWorktrees(
    private val clone: Path,
    private val target: TargetProject,
    private val root: Path,
    private val indexes: IndexDirectories = IndexDirectories.default(),
) : RunWorktrees {
    /** The git common directory of every run worktree, which keys their Index Seed. */
    val gitCommonDir: Path get() = clone.resolve(".git")

    /** Clones [target] once, and checks that its tag still points at the pinned commit. */
    fun ensureClone() {
        if (!gitCommonDir.exists()) {
            clone.parent.createDirectories()
            Processes.output("git", "clone", "--quiet", "--no-checkout", target.repository, clone.toString())
        }
        val tagged = Processes.output("git", "rev-parse", "${target.tag}^{commit}", dir = clone)
        check(tagged == target.commit) { "${target.repository} tag ${target.tag} is now $tagged, not the pinned ${target.commit}" }
    }

    override fun create(name: String): Path {
        val worktree = root.resolve(name)
        if (worktree.exists()) remove(worktree) // left behind by a Benchmark that was killed
        root.createDirectories()
        git("worktree", "add", "--quiet", "--detach", worktree.toString(), target.commit)
        return worktree.toRealPath()
    }

    /**
     * Deletes [worktree] and Kronstadt's index for it. A Gradle or Kotlin daemon from the check may still be writing into
     * its build output, so the deletion is retried, and git's record of it pruned afterwards.
     */
    override fun remove(worktree: Path) {
        val index = indexes.forWorktree(worktree).parent
        runCatching { git("worktree", "remove", "--force", worktree.toString()) }
        var attempt = 1
        while (true) {
            try {
                worktree.deleteRecursively()
                break
            } catch (e: IOException) {
                if (attempt++ == DELETE_ATTEMPTS) throw IOException("could not delete run worktree $worktree", e)
                Thread.sleep(1000)
            }
        }
        git("worktree", "prune")
        index.deleteRecursively()
    }

    private fun git(vararg args: String) = Processes.output("git", *args, dir = clone)

    private companion object {
        const val DELETE_ATTEMPTS = 5
    }
}
