@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Where each worktree's kotlin-lsp index lives, under Kronstadt's own [cacheRoot] (never JetBrains' default cache).
 *
 * Layout, one directory per owner, keyed by a hash of the owner's real path:
 * ```
 * <cacheRoot>/index/<hash>/worktree         the worktree's path
 * <cacheRoot>/index/<hash>/lock             locked by the one instance serving the worktree; holds its pid
 * <cacheRoot>/index/<hash>/kotlin-server/   passed to kotlin-lsp as `initializationOptions.indexDir`
 * <cacheRoot>/seed/<hash>/git-common-dir    the repo's `git rev-parse --git-common-dir`
 * <cacheRoot>/seed/<hash>/kotlin-server/    the repo's Index Seed
 * <cacheRoot>/seed/<hash>/.kotlin-server-<millis>-<uuid>/          a refresh's temporary clone
 * <cacheRoot>/seed/<hash>/.kotlin-server-retired-<millis>-<uuid>/  a seed a refresh replaced, until [prune] deletes it
 * ```
 *
 * Only one instance per worktree may use its index, since kotlin-lsp locks it: each instance [claim]s it first.
 */
public class IndexDirectories(
    public val cacheRoot: Path,
    /** The current time, to name and age the leftovers [prune] deletes. */
    private val clock: () -> Instant = Instant::now,
) {
    private val indexes = cacheRoot.resolve("index")
    private val seeds = cacheRoot.resolve("seed")

    /**
     * The directory kotlin-lsp keeps [worktree]'s index in. Records [worktree] next to it, but leaves the directory
     * itself to kotlin-lsp, which creates a missing one and builds the index cold.
     */
    public fun forWorktree(worktree: Path): Path = recordOwner(indexes, WORKTREE_FILE, worktree)

    /**
     * Claims [worktree]'s index for this instance alone, with an exclusive, non-blocking lock on its `lock` file, which
     * sits outside the directory [forWorktree] returns, so no Index Seed ever holds it. Never waits: if another instance
     * holds it, says so at once, with that instance's pid if it could be read. [prune] neither takes nor waits on it.
     */
    public fun claim(worktree: Path): IndexClaim = IndexClaim.take(forWorktree(worktree).resolveSibling(LOCK_FILE))

    /**
     * The Index Seed slot shared by every worktree of the repo whose git common dir is [gitCommonDir]. Pass it
     * absolute: `git rev-parse --git-common-dir` may print a path relative to the worktree.
     */
    public fun seedSlot(gitCommonDir: Path): Path = recordOwner(seeds, GIT_COMMON_DIR_FILE, gitCommonDir)

    /**
     * Deletes every index directory whose recorded worktree is confirmed gone, and every seed slot whose recorded git
     * common dir is. A directory without a readable record, or whose owner cannot be checked (e.g. no permission), is
     * left alone: it may belong to an instance that is starting right now.
     *
     * In the seed slots left, also deletes the temporary clones and retired seeds older than [LEFTOVER_AGE], which
     * killed or finished refreshes left behind; younger ones may still be in use by a refresh, or by a worktree cloning
     * them.
     */
    public fun prune() {
        pruneWhereGone(indexes, WORKTREE_FILE)
        pruneWhereGone(seeds, GIT_COMMON_DIR_FILE)
        pruneSeedLeftovers()
    }

    /** A new hidden sibling of [dir] to clone into, then move to [dir]; named with the time, for [prune]. */
    internal fun temporarySibling(dir: Path): Path = timedSibling(dir, "")

    /** A new hidden sibling of [dir] to move a seed swapped out of [dir] to; named with the time, for [prune]. */
    internal fun retiredSibling(dir: Path): Path = timedSibling(dir, "$RETIRED-")

    private fun timedSibling(dir: Path, kind: String): Path =
        dir.resolveSibling(".${dir.fileName}-$kind${clock().toEpochMilli()}-${UUID.randomUUID()}")

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

    private fun pruneSeedLeftovers() {
        if (!Files.isDirectory(seeds)) return
        val slots = Files.list(seeds).use { it.toList() }.filter { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) }
        val now = clock()
        for (slot in slots) {
            val leftovers = try {
                Files.list(slot).use { entries ->
                    entries.filter { it.fileName.toString().startsWith(".$LSP_INDEX_DIR-") }.toList()
                }
            } catch (_: IOException) {
                continue // Pruned meanwhile by another instance, or unreadable.
            }
            for (leftover in leftovers) {
                val since = madeAt(leftover) ?: continue
                if ((now.toEpochMilli() - since.toEpochMilli()).milliseconds <= LEFTOVER_AGE) continue
                try {
                    // Deletes symbolic links, never follows them.
                    leftover.deleteRecursively()
                } catch (e: IOException) {
                    System.err.println("kronstadt: could not delete Index Seed leftover $leftover: ${e.message}")
                }
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

        /** File in each index directory that the instance serving its worktree locks; see [claim]. */
        public const val LOCK_FILE: String = "lock"
        private const val LSP_INDEX_DIR = "kotlin-server"

        private const val RETIRED = "retired"

        /** How old a temporary clone or retired seed must be before no refresh or clone can still be using it. */
        internal val LEFTOVER_AGE: Duration = 10.minutes

        /** A [temporarySibling] or [retiredSibling] name: the epoch millis it was made at, in group 1. */
        private val TIMED_LEFTOVER = Regex("""^\.$LSP_INDEX_DIR-(?:$RETIRED-)?(\d{13,})-""")

        /**
         * When [leftover] was made: the time in its name, or else, for one named before names had a time, when it was
         * last modified. Null if that cannot be read.
         */
        private fun madeAt(leftover: Path): Instant? {
            val named = TIMED_LEFTOVER.find(leftover.fileName.toString())
            if (named != null) return Instant.ofEpochMilli(named.groupValues[1].toLong())
            return try {
                Files.getLastModifiedTime(leftover, LinkOption.NOFOLLOW_LINKS).toInstant()
            } catch (_: IOException) {
                null
            }
        }

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
