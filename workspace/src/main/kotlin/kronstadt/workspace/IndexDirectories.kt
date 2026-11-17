@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively

/**
 * Where each worktree's kotlin-lsp index lives, under Kronstadt's own [cacheRoot] (never JetBrains' default cache).
 *
 * Layout, one directory per owner, keyed by a hash of the owner's real path:
 * ```
 * <cacheRoot>/index/<hash>/worktree         the worktree's path
 * <cacheRoot>/index/<hash>/kotlin-server/   passed to kotlin-lsp as `initializationOptions.indexDir`
 * <cacheRoot>/seed/<hash>/git-common-dir    the repo's `git rev-parse --git-common-dir`
 * <cacheRoot>/seed/<hash>/kotlin-server/    the repo's Index Seed
 * ```
 */
public class IndexDirectories(public val cacheRoot: Path) {
    private val indexes = cacheRoot.resolve("index")
    private val seeds = cacheRoot.resolve("seed")

    /**
     * The directory kotlin-lsp keeps [worktree]'s index in. Records [worktree] next to it, but leaves the directory
     * itself to kotlin-lsp, which creates a missing one and builds the index cold.
     */
    public fun forWorktree(worktree: Path): Path = recordOwner(indexes, WORKTREE_FILE, worktree)

    /**
     * The Index Seed slot shared by every worktree of the repo whose git common dir is [gitCommonDir]. Pass it
     * absolute: `git rev-parse --git-common-dir` may print a path relative to the worktree.
     */
    public fun seedSlot(gitCommonDir: Path): Path = recordOwner(seeds, GIT_COMMON_DIR_FILE, gitCommonDir)

    /**
     * Deletes every index directory whose recorded worktree is confirmed gone, and every seed slot whose recorded git
     * common dir is. A directory without a readable record, or whose owner cannot be checked (e.g. no permission), is
     * left alone: it may belong to an instance that is starting right now.
     */
    public fun prune() {
        pruneWhereGone(indexes, WORKTREE_FILE)
        pruneWhereGone(seeds, GIT_COMMON_DIR_FILE)
    }

    /** Creates [owner]'s directory under [parent], records [owner] in it, and returns where its index goes. */
    private fun recordOwner(parent: Path, recordFile: String, owner: Path): Path {
        val path = owner.toRealPath()
        val ownerDir = parent.resolve(key(path))
        Files.createDirectories(ownerDir)
        writeAtomically(ownerDir.resolve(recordFile), path.toString())
        return ownerDir.resolve(LSP_INDEX_DIR)
    }

    private fun pruneWhereGone(parent: Path, recordFile: String) {
        if (!Files.isDirectory(parent)) return
        val entries = Files.list(parent).use { it.toList() }
        for (dir in entries) {
            if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) continue
            val recorded = try {
                Files.readString(dir.resolve(recordFile)).trim()
            } catch (_: IOException) {
                continue
            }
            if (recorded.isEmpty() || !Files.notExists(Path.of(recorded))) continue
            try {
                // Deletes symbolic links, never follows them.
                dir.deleteRecursively()
            } catch (e: IOException) {
                System.err.println("kronstadt: could not delete stale index $dir: ${e.message}")
            }
        }
    }

    public companion object {
        /** Kronstadt's cache on macOS, the only platform v1 seeds on. */
        public fun default(): IndexDirectories =
            IndexDirectories(Path.of(System.getProperty("user.home"), "Library", "Caches", "kronstadt"))

        /** File in each index directory that records the worktree it belongs to. */
        public const val WORKTREE_FILE: String = "worktree"

        /** File in each seed slot that records the git common dir of the repo it belongs to. */
        public const val GIT_COMMON_DIR_FILE: String = "git-common-dir"
        private const val LSP_INDEX_DIR = "kotlin-server"

        internal fun key(path: Path): String =
            MessageDigest.getInstance("SHA-256").digest(path.toString().toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)

        private fun writeAtomically(file: Path, text: String) {
            val temp = Files.createTempFile(file.parent, ".${file.fileName}", ".tmp")
            try {
                Files.writeString(temp, "$text\n")
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally {
                Files.deleteIfExists(temp)
            }
        }
    }
}
