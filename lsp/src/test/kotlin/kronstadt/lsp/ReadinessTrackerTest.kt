@file:OptIn(ExperimentalCoroutinesApi::class) // testTimeSource

package kronstadt.lsp

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kronstadt.core.domain.Readiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class ReadinessTrackerTest {
    private fun TestScope.tracker() = ReadinessTracker(testTimeSource)

    @Test
    fun `is starting before the server has said anything`() = runTest {
        assertEquals(Readiness.NotReady("Starting", 5.seconds), tracker().awaitReady(5.seconds))
    }

    @Test
    fun `reports the title of the open progress as the phase`() = runTest {
        val tracker = tracker()
        tracker.progressBegin("t1", "Importing")
        tracker.progressBegin("t2", "Indexing")
        assertEquals(Readiness.NotReady("Indexing", 5.seconds), tracker.awaitReady(5.seconds))
        tracker.progressEnd("t2")
        assertEquals(Readiness.NotReady("Importing", 5.seconds), tracker.awaitReady(5.seconds))
    }

    @Test
    fun `is not ready while progress is open, even after the import finished`() = runTest {
        val tracker = tracker()
        tracker.progressBegin("t1", "Indexing")
        tracker.importState("FINISHED")
        assertEquals(Readiness.NotReady("Indexing", 5.seconds), tracker.awaitReady(5.seconds))
    }

    @Test
    fun `is not ready when all progress ended but the import has not finished`() = runTest {
        val tracker = tracker()
        tracker.progressBegin("t1", "Importing")
        tracker.progressEnd("t1")
        assertEquals(Readiness.NotReady("Importing", 5.seconds), tracker.awaitReady(5.seconds))
    }

    @Test
    fun `waits until the import finished and all progress ended`() = runTest {
        val tracker = tracker()
        tracker.progressBegin("t1", "Importing")
        launch {
            delay(10.seconds)
            tracker.progressEnd("t1")
            tracker.importState("FINISHED")
        }
        val start = testTimeSource.markNow()
        assertEquals(Readiness.Ready, tracker.awaitReady(90.seconds))
        assertEquals(10.seconds + ReadinessTracker.SETTLE, start.elapsedNow())
    }

    @Test
    fun `answers immediately when ready, even with no time left to wait`() = runTest {
        val tracker = tracker()
        tracker.importState("FINISHED")
        assertEquals(Readiness.Ready, tracker.awaitReady(0.seconds))
    }

    @Test
    fun `is not ready in the gap between two progress runs, until the settle period has passed`() = runTest {
        // kotlin-lsp, after a re-import: Indexing ends, and the next Indexing begins milliseconds later.
        val tracker = tracker()
        tracker.importState("FINISHED")
        tracker.progressBegin("t1", "Indexing")
        tracker.progressEnd("t1")
        assertEquals(Readiness.NotReady("Indexing", 0.seconds), tracker.awaitReady(0.seconds))
        launch {
            delay(ReadinessTracker.SETTLE / 2)
            tracker.progressBegin("t2", "Indexing")
            delay(3.seconds)
            tracker.progressEnd("t2")
        }
        val start = testTimeSource.markNow()
        assertEquals(Readiness.Ready, tracker.awaitReady(90.seconds))
        assertEquals(ReadinessTracker.SETTLE / 2 + 3.seconds + ReadinessTracker.SETTLE, start.elapsedNow())
    }

    @Test
    fun `is ready once the settle period after the last progress has passed`() = runTest {
        val tracker = tracker()
        tracker.importState("FINISHED")
        tracker.progressBegin("t1", "Indexing")
        tracker.progressEnd("t1")
        delay(ReadinessTracker.SETTLE)
        assertEquals(Readiness.Ready, tracker.awaitReady(0.seconds))
    }

    @Test
    fun `becomes not ready again when a new import or indexing run starts`() = runTest {
        val tracker = tracker()
        tracker.importState("FINISHED")
        tracker.progressBegin("t3", "Indexing")
        assertEquals(Readiness.NotReady("Indexing", 5.seconds), tracker.awaitReady(5.seconds))
        tracker.progressEnd("t3")
        tracker.importState("IN_PROGRESS")
        assertEquals(Readiness.NotReady("IN_PROGRESS", 5.seconds), tracker.awaitReady(5.seconds))
    }

    @Test
    fun `the index is complete as soon as the import finished and the last progress ended, before readiness settles`() = runTest {
        val tracker = tracker()
        tracker.importState("FINISHED")
        tracker.progressBegin("t1", "Indexing")
        tracker.progressEnd("t1")
        delay(ReadinessTracker.SETTLE / 2)
        assertTrue(tracker.indexComplete)
        assertEquals(Readiness.NotReady("Indexing", 0.seconds), tracker.awaitReady(0.seconds))
    }

    @Test
    fun `the index is not complete while progress is open, even after the import finished`() = runTest {
        val tracker = tracker()
        tracker.importState("FINISHED")
        tracker.progressBegin("t1", "Indexing")
        assertFalse(tracker.indexComplete)
    }

    @Test
    fun `the index is not complete before the import finished, even with all progress ended`() = runTest {
        val tracker = tracker()
        tracker.progressBegin("t1", "Importing")
        tracker.progressEnd("t1")
        assertFalse(tracker.indexComplete)
    }
}
