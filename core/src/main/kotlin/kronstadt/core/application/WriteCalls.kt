package kronstadt.core.application

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kronstadt.core.port.ContentHash
import java.nio.file.Path

/**
 * Runs write calls one at a time (ADR 0003) and owns the Rejected Edit: one per Kronstadt instance, shared by every use
 * case that writes. Read tools don't use it. Every write call drops the Rejected Edit kept so far, unless it is an amend
 * naming it: then it is handed over, and the amend's [Settlement] decides what is kept after it.
 */
public class WriteCalls {
    private val mutex = Mutex()
    private var rejected: RejectedEdit? = null
    private var lastId = 0

    /**
     * Runs an Edit's [block] alone. With [amend] naming the Rejected Edit kept so far, [block] is handed it; with [amend]
     * naming another id (or none kept), [block] doesn't run: the result is [EditResult.NoRejectedEdit] and the Rejected
     * Edit, if any, stays. Without [amend], [block] gets null and the Rejected Edit is dropped. [block]'s [Settlement]
     * decides what is kept after the call, and the result names it as its [EditResult.rejectedEdit].
     */
    internal suspend fun edit(amend: String?, block: suspend (RejectedEdit?) -> Settled): EditResult = mutex.withLock {
        val current = rejected
        if (amend != null && current?.id != amend) return@withLock EditResult.NoRejectedEdit(amend, current?.id)
        rejected = null
        val taken = if (amend == null) null else current
        val settled = block(taken)
        settled.result.withRejectedEdit(settle(settled.settlement, taken))
    }

    /** Runs a rename's [block] alone; a rename settles [Settlement.Drop], whatever its outcome. */
    internal suspend fun <T> rename(block: suspend () -> T): T = mutex.withLock {
        settle(Settlement.Drop, null)
        block()
    }

    /** Applies [settlement] to [taken], the Rejected Edit the call was handed; returns the id of the one kept, if any. */
    private fun settle(settlement: Settlement, taken: RejectedEdit?): String? {
        rejected = when (settlement) {
            is Settlement.Keep -> RejectedEdit("e${++lastId}", settlement.operations, settlement.bases)
            Settlement.Restore -> taken
            Settlement.Drop -> null
        }
        return rejected?.id
    }
}

/** What an Edit leaves of the Rejected Edit; every outcome of an Edit names one. */
internal sealed interface Settlement {
    /** [operations], read at [bases], become the Rejected Edit, under a new id: Validation rejected them. */
    data class Keep(val operations: List<Operation>, val bases: Map<Path, ContentHash?>) : Settlement

    /**
     * The Rejected Edit the call was handed is put back, under its id: the amend failed through no fault of the Rejected
     * Edit's. A call that wasn't handed one keeps none.
     */
    data object Restore : Settlement

    /** No Rejected Edit is kept. */
    data object Drop : Settlement
}

/** An Edit's [result], and what it leaves of the Rejected Edit. */
internal class Settled(val result: EditResult, val settlement: Settlement)

/** This result naming [id] as the Rejected Edit kept after it. */
private fun EditResult.withRejectedEdit(id: String?): EditResult = when (this) {
    is EditResult.Written -> copy(rejectedEdit = id)
    is EditResult.ValidationFailed -> copy(rejectedEdit = id)
    is EditResult.WriteBackFailed -> copy(rejectedEdit = id)
    is EditResult.OperationFailed -> copy(rejectedEdit = id)
    is EditResult.NoRejectedEdit -> copy(rejectedEdit = id)
    is EditResult.NotReady -> copy(rejectedEdit = id)
}

/**
 * An Edit that failed Validation, kept in memory until the next write call so a follow-up can amend it; nothing of it is
 * written. Only [WriteCalls] takes, keeps, restores and drops it. [bases] are the content hashes of every file it touched
 * as it read them, null for a file it creates.
 */
internal class RejectedEdit(val id: String, val operations: List<Operation>, val bases: Map<Path, ContentHash?>)
