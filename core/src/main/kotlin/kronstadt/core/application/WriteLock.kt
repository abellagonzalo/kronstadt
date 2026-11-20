package kronstadt.core.application

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kronstadt.core.port.ContentHash
import java.nio.file.Path

/**
 * Serializes write tools (ADR 0003): one per Kronstadt instance, shared by every use case that writes. Read tools
 * don't take it. It also keeps the last Rejected Edit, which every write call takes out of it (so drops it) and only an
 * Edit rejected again puts back.
 */
public class WriteLock {
    private val mutex = Mutex()
    private var rejected: RejectedEdit? = null
    private var lastId = 0

    /** Runs [block] alone, with the Rejected Edit kept so far, if any, which is dropped: [block] may [keep] another. */
    internal suspend fun <T> withLock(block: suspend (RejectedEdit?) -> T): T = mutex.withLock {
        val taken = rejected
        rejected = null
        block(taken)
    }

    /** Keeps [operations], based on [bases], as the Rejected Edit until the next write call; returns its new id. */
    internal fun keep(operations: List<Operation>, bases: Map<Path, ContentHash?>): String {
        check(mutex.isLocked) { "a Rejected Edit is kept under the write lock" }
        val id = "e${++lastId}"
        rejected = RejectedEdit(id, operations, bases)
        return id
    }
}

/**
 * An Edit that failed Validation, kept in memory until the next write call so a follow-up can amend it; nothing of it is
 * written. [bases] are the content hashes of every file it touched as it read them, null for a file it creates.
 */
internal class RejectedEdit(val id: String, val operations: List<Operation>, val bases: Map<Path, ContentHash?>)
