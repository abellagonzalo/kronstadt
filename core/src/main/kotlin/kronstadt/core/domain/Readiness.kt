package kronstadt.core.domain

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether the code intelligence has a complete project index, so project-wide answers are trustworthy. */
public sealed interface Readiness {
    public data object Ready : Readiness

    /** Still not ready after waiting [elapsed]; [phase] names what it is busy with (e.g. "Importing", "Indexing"). */
    public data class NotReady(val phase: String, val elapsed: Duration) : Readiness

    public companion object {
        /** How long tools that need the index wait for it by default. */
        public val DEFAULT_TIMEOUT: Duration = 90.seconds
    }
}
