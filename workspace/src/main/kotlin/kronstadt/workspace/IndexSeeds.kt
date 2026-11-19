@file:OptIn(ExperimentalPathApi::class) // deleteRecursively

package kronstadt.workspace

import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How a worktree's index directory starts. */
public sealed interface Seeding {
    /** Cloned from the repo's Index Seed. */
    public data object Seeded : Seeding

    /** The worktree already has an index directory; kotlin-lsp reconciles it. */
    public data object AlreadyIndexed : Seeding

    /** kotlin-lsp builds the index from scratch, for [reason]. */
    public data class Cold(val reason: String) : Seeding
}

/** What refreshing the Index Seed did. */
public sealed interface SeedRefresh {
    public data object Refreshed : SeedRefresh

    /** The seed slot was left as it was, for [reason]. */
    public data class Skipped(val reason: String) : SeedRefresh
}

/** A worktree's index directory, as prepared for kotlin-lsp. */
public class WorktreeIndex internal constructor(
    /** Pass to kotlin-lsp as `initializationOptions.indexDir`. */
    public val dir: Path,
    public val seeding: Seeding,
    /** The repo's seed slot; null outside a git repository. */
    internal val seedSlot: Path?,
)

/**
 * Seeds new worktrees' index directories from their repo's Index Seed, and refreshes the seed on clean shutdown.
 *
 * Only ever clones the seed slot or an index whose kotlin-lsp has exited, never a live sibling's index, and only
 * copy-on-write: where [copyOnWrite] cannot clone, the worktree indexes cold instead of waiting on a 1 GB copy.
 */
public class IndexSeeds(
    private val directories: IndexDirectories,
    private val copyOnWrite: CopyOnWrite = MacOsCopyOnWrite,
    /** How long [refresh] waits for an exited kotlin-lsp's index locks to be released. */
    private val lockWait: Duration = 10.seconds,
) {
    /**
     * Prepares [worktree]'s index directory before kotlin-lsp starts: a worktree without one gets a clone of its
     * repo's Index Seed, if there is one.
     */
    public fun prepare(worktree: Path): WorktreeIndex {
        val dir = directories.forWorktree(worktree)
        val slot = gitCommonDir(worktree)?.let(directories::seedSlot)
        val seeding = when {
            dir.exists() -> Seeding.AlreadyIndexed
            slot == null -> Seeding.Cold("not a git repository")
            !slot.isDirectory() -> Seeding.Cold("no Index Seed yet")
            else -> cloneInto(slot, dir)
        }
        return WorktreeIndex(dir, seeding, slot)
    }

    /**
     * Replaces the repo's Index Seed with a clone of [index]. Call only after kotlin-lsp has exited cleanly, with a
     * complete index.
     */
    public fun refresh(index: WorktreeIndex): SeedRefresh {
        val slot = index.seedSlot ?: return SeedRefresh.Skipped("not a git repository")
        if (!index.dir.isDirectory()) return SeedRefresh.Skipped("no index to seed from")
        stillLocked(index.dir)?.let { return SeedRefresh.Skipped("$it is still locked") }
        val temp = temporarySibling(slot)
        try {
            copyOnWrite.clone(index.dir, temp)
            val placed = !slot.exists() && try {
                Files.move(temp, slot, StandardCopyOption.ATOMIC_MOVE)
                true
            } catch (_: IOException) {
                false // Another instance refreshed the slot first.
            }
            if (!placed) copyOnWrite.swap(temp, slot)
            return SeedRefresh.Refreshed
        } catch (e: Exception) {
            return SeedRefresh.Skipped("could not clone the index: ${e.message}")
        } finally {
            // After a swap, the old seed.
            deleteQuietly(temp)
        }
    }

    /**
     * Waits up to [exitWait] for [languageServer], the process of kotlin-lsp that was asked to exit, to end, then
     * [refresh]es the seed from [index]. A kotlin-lsp still running then is killed, and its index left out.
     * For a process that isn't kotlin-lsp's parent, so cannot read its exit code: call only after kotlin-lsp answered
     * `shutdown`, so that it closes its index and exits by itself.
     */
    public fun refreshAfterExit(languageServer: ProcessHandle, index: WorktreeIndex, exitWait: Duration): SeedRefresh {
        try {
            languageServer.onExit().get(exitWait.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            languageServer.destroyForcibly()
            return SeedRefresh.Skipped("kotlin-lsp did not exit within $exitWait")
        }
        return refresh(index)
    }

    /**
     * Waits up to [lockWait] for every RocksDB `LOCK` file in [dir] to be unlocked; returns one still locked, if any.
     * RocksDB holds a POSIX lock on it while open, so a copy taken meanwhile may be inconsistent.
     */
    private fun stillLocked(dir: Path): Path? {
        val locks = Files.walk(dir).use { paths -> paths.filter { it.fileName.toString() == "LOCK" }.toList() }
        val deadline = TimeSource.Monotonic.markNow() + lockWait
        for (lock in locks) {
            while (isLocked(lock)) {
                if (deadline.hasPassedNow()) return lock
                Thread.sleep(POLL.inWholeMilliseconds)
            }
        }
        return null
    }

    private fun cloneInto(slot: Path, dir: Path): Seeding {
        deleteTemporarySiblings(dir)
        val temp = temporarySibling(dir)
        return try {
            copyOnWrite.clone(slot, temp)
            Files.move(temp, dir, StandardCopyOption.ATOMIC_MOVE)
            Seeding.Seeded
        } catch (e: Exception) {
            Seeding.Cold("could not clone the Index Seed: ${e.message}")
        } finally {
            deleteQuietly(temp)
        }
    }

    private companion object {
        private val POLL = 50.milliseconds

        private fun isLocked(file: Path): Boolean = try {
            FileChannel.open(file, StandardOpenOption.WRITE).use { channel ->
                val lock = channel.tryLock() ?: return true
                lock.release()
                false
            }
        } catch (_: NoSuchFileException) {
            false
        }

        private fun temporarySibling(dir: Path): Path = dir.resolveSibling(".${dir.fileName}-${UUID.randomUUID()}")

        /** Deletes clones a killed instance left next to [dir]; only this worktree's own instance writes there. */
        private fun deleteTemporarySiblings(dir: Path) {
            val prefix = ".${dir.fileName}-"
            Files.list(dir.parent).use { entries ->
                entries.filter { it.fileName.toString().startsWith(prefix) }.toList()
            }.forEach(::deleteQuietly)
        }

        private fun deleteQuietly(dir: Path) {
            try {
                dir.deleteRecursively()
            } catch (e: IOException) {
                System.err.println("kronstadt: could not delete $dir: ${e.message}")
            }
        }

        /** The absolute `git rev-parse --git-common-dir` of [worktree], or null if it isn't in a git repository. */
        private fun gitCommonDir(worktree: Path): Path? = try {
            val process = ProcessBuilder(
                "git", "-C", worktree.toString(), "rev-parse", "--path-format=absolute", "--git-common-dir",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            if (process.waitFor(10, TimeUnit.SECONDS) && process.exitValue() == 0 && output.isNotEmpty()) {
                Path.of(output)
            } else {
                null
            }
        } catch (_: IOException) {
            null
        }
    }
}
