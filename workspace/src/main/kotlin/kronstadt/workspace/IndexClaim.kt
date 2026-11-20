package kronstadt.workspace

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * The outcome of claiming a worktree's index for this instance alone ([IndexDirectories.claim]): kotlin-lsp's RocksDB
 * index takes an exclusive lock, so only one instance per worktree can use it.
 */
public sealed interface IndexClaim {
    /**
     * This instance holds the worktree's index until [release], or until the process exits: the OS releases the lock
     * then, so a killed instance never leaves a stale one.
     */
    public class Held internal constructor(
        /** The file the lock is taken on; outside kotlin-lsp's index directory, so never part of an Index Seed. */
        public val lockFile: Path,
        private val channel: FileChannel,
    ) : IndexClaim {
        /** Lets another claim succeed. A server never calls this: it holds the claim for its whole life. */
        public fun release() {
            synchronized(held) {
                held.remove(lockFile)
                channel.close()
            }
        }
    }

    /** Another instance holds the worktree's index; [pid] is its process id, null if it could not be read. */
    public data class HeldByAnother(val pid: Long?) : IndexClaim

    /** The lock could not be taken for [reason] other than another holder, e.g. a filesystem without locking. */
    public data class Unavailable(val reason: String) : IndexClaim

    public companion object {
        /**
         * The claims this process holds, by lock file. A POSIX lock belongs to the process, and closing any channel on
         * its file releases it, so a second claim in this process must never open the file. Also keeps each [Held]'s
         * channel reachable, so it isn't closed when garbage collected.
         */
        private val held = mutableMapOf<Path, Held>()

        /** Takes an exclusive, non-blocking lock on [lockFile], creating it, and writes this process's pid into it. */
        internal fun take(lockFile: Path): IndexClaim = synchronized(held) {
            val pid = ProcessHandle.current().pid()
            if (lockFile in held) return HeldByAnother(pid)
            val channel = try {
                FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE)
            } catch (e: IOException) {
                return Unavailable("could not open $lockFile: ${e.message}")
            }
            val lock = try {
                channel.tryLock()
            } catch (e: IOException) {
                channel.close()
                return Unavailable("could not lock $lockFile: ${e.message}")
            } catch (_: OverlappingFileLockException) {
                channel.close()
                return HeldByAnother(pid)
            }
            if (lock == null) {
                val holder = readPid(channel)
                channel.close()
                return HeldByAnother(holder)
            }
            try {
                channel.truncate(0)
                channel.write(ByteBuffer.wrap("$pid\n".toByteArray()), 0)
                channel.force(false)
            } catch (e: IOException) {
                // Only the other instance's message needs the pid, and it does without.
                System.err.println("kronstadt: could not write this pid to $lockFile: ${e.message}")
            }
            Held(lockFile, channel).also { held[lockFile] = it }
        }

        /** The pid the holder wrote into the lock file, or null if it hasn't yet or it can't be read. */
        private fun readPid(channel: FileChannel): Long? = try {
            val buffer = ByteBuffer.allocate(32)
            channel.read(buffer, 0)
            String(buffer.array(), 0, buffer.position()).trim().toLongOrNull()
        } catch (_: IOException) {
            null
        }
    }
}
