package kronstadt.server

import kronstadt.workspace.IndexClaim
import java.nio.file.Path

/**
 * Starts code intelligence for [worktree]: claims its index, prunes the cache, prepares the index and starts the
 * language server on it, in that order. If another instance holds the index, nothing is pruned, seeded or started,
 * and this throws [worktreeInUse]'s message, which every tool call that needs analysis then fails with.
 */
internal inline fun <I, S> startUp(
    worktree: Path,
    claim: () -> IndexClaim,
    prune: () -> Unit,
    prepare: () -> I,
    start: (I) -> S,
): S {
    when (val claimed = claim()) {
        is IndexClaim.Held -> {}
        is IndexClaim.HeldByAnother -> throw IllegalStateException(worktreeInUse(claimed.pid, worktree))
        // The lock must never stop a sole instance from starting.
        is IndexClaim.Unavailable -> System.err.println("kronstadt: starting without the index lock: ${claimed.reason}")
    }
    try {
        prune()
    } catch (e: Exception) {
        // Pruning only frees disk; it must never stop kotlin-lsp from starting.
        System.err.println("kronstadt: could not prune the index cache: ${e.message}")
    }
    return start(prepare())
}

/** Why a second instance on [worktree] can't analyse anything, and how the agent gets going again. */
internal fun worktreeInUse(pid: Long?, worktree: Path): String =
    "Another Kronstadt${pid?.let { " (pid $it)" } ?: ""} is already serving $worktree, and only one instance per " +
        "worktree can use its index. Close that session and then reconnect Kronstadt (`/mcp` in Claude Code), " +
        "or start this session in a separate Git worktree."
