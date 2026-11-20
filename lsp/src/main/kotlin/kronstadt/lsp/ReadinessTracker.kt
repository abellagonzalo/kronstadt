package kronstadt.lsp

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.Readiness
import kotlin.time.ComparableTimeMark
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** One notification the tracker was fed, e.g. `begin Indexing`, `end Indexing` or `import FINISHED`, and when. */
internal data class ReadinessSignal(val text: String, val at: ComparableTimeMark)

/**
 * Live readiness of kotlin-lsp, fed by its notifications for the whole session.
 *
 * Ready means `intellij/workspaceImportState` last reported [FINISHED], no `$/progress` token is open, and none
 * has been for [SETTLE]. kotlin-lsp answers `initialize` before importing, and `workspace/symbol` is silently
 * empty until it is ready.
 *
 * The settle period closes the gaps between progress runs (#37, pinned by `ReimportContractTest` on 271.2085.0).
 * kotlin-lsp watches the build files itself and re-imports when one changes, without a
 * `workspace/didChangeWatchedFiles` from us. During a re-import it reports no import phase but [FINISHED], so open
 * progress is the only sign it is busy, and that progress has gaps: ~0.5 s of nothing between the short Indexing run
 * that notices the change and the Importing that follows, and a few ms between the Indexing runs after [FINISHED],
 * both on first startup and after a re-import.
 */
internal class ReadinessTracker(private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic) {
    private data class State(
        /** Last `intellij/workspaceImportState` phase, or null if none was reported yet. */
        val importPhase: String? = null,
        /** Open `$/progress` tokens and their titles, in the order they began. */
        val openProgress: Map<String, String> = emptyMap(),
        /** Title of the most recently begun progress, open or not. */
        val lastTitle: String? = null,
        /** When the last open progress ended, or null if none ever did. */
        val quietSince: TimeMark? = null,
    ) {
        /** Ready but for the settle period. */
        val idle: Boolean get() = importPhase == FINISHED && openProgress.isEmpty()

        /** How long [idle] still has to last before it counts as ready. */
        val settleLeft: Duration get() = quietSince?.let { SETTLE - it.elapsedNow() }?.coerceAtLeast(Duration.ZERO) ?: Duration.ZERO

        val ready: Boolean get() = idle && settleLeft == Duration.ZERO

        /** The most recently begun open progress says best what the server is busy with. */
        val phase: String
            get() = openProgress.values.lastOrNull() ?: importPhase?.takeIf { it != FINISHED } ?: lastTitle ?: "Starting"
    }

    private val state = MutableStateFlow(State())

    private val _signals = MutableSharedFlow<ReadinessSignal>(extraBufferCapacity = 1024, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Every signal from here on, in order; for contract tests that pin what kotlin-lsp sends. Never suspends the client. */
    val signals: SharedFlow<ReadinessSignal> = _signals.asSharedFlow()

    private fun signal(text: String) {
        _signals.tryEmit(ReadinessSignal(text, timeSource.markNow()))
    }

    fun importState(phase: String) {
        signal("import $phase")
        state.update { it.copy(importPhase = phase) }
    }

    fun progressBegin(token: String, title: String) {
        signal("begin $title")
        state.update { it.copy(openProgress = it.openProgress + (token to title), lastTitle = title) }
    }

    fun progressEnd(token: String) {
        signal("end ${state.value.openProgress[token] ?: token}")
        state.update {
            val open = it.openProgress - token
            val endedLast = open.isEmpty() && token in it.openProgress
            it.copy(openProgress = open, quietSince = if (endedLast) timeSource.markNow() else it.quietSince)
        }
    }

    /**
     * Whether the import [FINISHED] and no progress is open right now, without the settle period [awaitReady] adds and
     * without waiting. For deciding at shutdown whether the index may become the Index Seed (#79), not for tool calls:
     * the settle period guards tool calls from empty answers between progress runs, while a seed only needs a finished
     * import, and a seed taken just before a re-import is still a fine warm start.
     */
    val indexComplete: Boolean get() = state.value.idle

    suspend fun awaitReady(timeout: Duration): Readiness {
        val start = timeSource.markNow()
        if (state.value.ready) return Readiness.Ready
        return withTimeoutOrNull(timeout) { awaitSettled() }
            ?.let { Readiness.Ready }
            ?: Readiness.NotReady(state.value.phase, start.elapsedNow())
    }

    /** Waits until idle, then for the settle period, starting over whenever the server says anything meanwhile. */
    private suspend fun awaitSettled() {
        while (true) {
            val idleState = state.first { it.idle }
            val settleLeft = idleState.settleLeft
            if (settleLeft == Duration.ZERO) return
            withTimeoutOrNull(settleLeft) { state.first { it != idleState } }
        }
    }

    companion object {
        const val FINISHED: String = "FINISHED"

        /** How long the server must stay idle before it counts as ready; well above the gaps seen (see the KDoc). */
        val SETTLE: Duration = 1.seconds
    }
}
