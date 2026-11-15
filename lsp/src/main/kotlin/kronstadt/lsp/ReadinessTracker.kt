package kronstadt.lsp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kronstadt.core.domain.Readiness
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * Live readiness of kotlin-lsp, fed by its notifications for the whole session.
 *
 * Ready means `intellij/workspaceImportState` last reported [FINISHED] and no `$/progress` token is open.
 * kotlin-lsp answers `initialize` before importing, and `workspace/symbol` is silently empty until it is ready.
 */
internal class ReadinessTracker(private val timeSource: TimeSource = TimeSource.Monotonic) {
    private data class State(
        /** Last `intellij/workspaceImportState` phase, or null if none was reported yet. */
        val importPhase: String? = null,
        /** Open `$/progress` tokens and their titles, in the order they began. */
        val openProgress: Map<String, String> = emptyMap(),
        /** Title of the most recently begun progress, open or not. */
        val lastTitle: String? = null,
    ) {
        val ready: Boolean get() = importPhase == FINISHED && openProgress.isEmpty()

        /** The most recently begun open progress says best what the server is busy with. */
        val phase: String
            get() = openProgress.values.lastOrNull() ?: importPhase?.takeIf { it != FINISHED } ?: lastTitle ?: "Starting"
    }

    private val state = MutableStateFlow(State())

    fun importState(phase: String) = state.update { it.copy(importPhase = phase) }

    fun progressBegin(token: String, title: String) = state.update {
        it.copy(openProgress = it.openProgress + (token to title), lastTitle = title)
    }

    fun progressEnd(token: String) = state.update { it.copy(openProgress = it.openProgress - token) }

    suspend fun awaitReady(timeout: Duration): Readiness {
        val start = timeSource.markNow()
        if (state.value.ready) return Readiness.Ready
        return withTimeoutOrNull(timeout) { state.first { it.ready } }
            ?.let { Readiness.Ready }
            ?: Readiness.NotReady(state.value.phase, start.elapsedNow())
    }

    companion object {
        const val FINISHED: String = "FINISHED"
    }
}
