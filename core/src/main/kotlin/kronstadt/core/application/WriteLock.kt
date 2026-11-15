package kronstadt.core.application

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes write tools (ADR 0003): one per Kronstadt instance, shared by every use case that writes. Read tools
 * don't take it.
 */
public class WriteLock {
    private val mutex = Mutex()

    internal suspend fun <T> withLock(block: suspend () -> T): T = mutex.withLock { block() }
}
