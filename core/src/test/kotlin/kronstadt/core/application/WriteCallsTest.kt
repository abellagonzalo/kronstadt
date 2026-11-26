package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.Readiness
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

/** The Rejected Edit's lifecycle across write calls: what each call is handed, and what its Settlement keeps after it. */
class WriteCallsTest {
    private val calls = WriteCalls()
    private val operation = EditIn("A.kt", null, "= 1", "= Duration.ZERO")
    private val fix = EditIn("A.kt", null, "package a\n", "package a\n\nimport kotlin.time.Duration\n")
    private val rejected = EditResult.ValidationFailed(ValidationFailure.Rejected(emptyList()))
    private val notReady = Readiness.NotReady("Indexing", 5.seconds)

    /** An Edit without amend that Validation rejects, keeping [operations]. */
    private suspend fun reject(operations: List<Operation> = listOf(operation)): EditResult =
        calls.edit(null) { taken ->
            assertNull(taken)
            Settled(rejected, Settlement.Keep(operations, emptyMap()))
        }

    /** An amend of [id], handed the Rejected Edit of [operation], that ends in [result] settling [settlement]. */
    private suspend fun amend(id: String, result: EditResult, settlement: Settlement): EditResult =
        calls.edit(id) { taken ->
            assertEquals(id, taken?.id)
            assertEquals(listOf(operation), taken?.operations)
            Settled(result, settlement)
        }

    /** The id of the Rejected Edit kept now, if any, as an amend naming no kept id is told, which changes nothing. */
    private suspend fun kept(): String? = calls.edit("e99") { error("an amend naming another id isn't handed a Rejected Edit") }.rejectedEdit

    @Test
    fun `a rejected Edit is kept under a new id, which the result names`() = runTest {
        assertEquals(rejected.copy(rejectedEdit = "e1"), reject())
        assertEquals("e1", kept())

        assertEquals("e2", reject().rejectedEdit)
        assertEquals("e2", kept())
    }

    @Test
    fun `an amend naming another id isn't run, and keeps the Rejected Edit`() = runTest {
        assertEquals(EditResult.NoRejectedEdit("e1"), calls.edit("e1") { error("not run") })
        reject()

        assertEquals(EditResult.NoRejectedEdit("e7", "e1"), calls.edit("e7") { error("not run") })
        assertEquals("e1", kept())
    }

    @Test
    fun `an Edit without amend isn't handed the Rejected Edit, and drops it even when it settles Restore`() = runTest {
        reject()

        val result = calls.edit(null) { taken ->
            assertNull(taken)
            Settled(EditResult.NotReady(notReady), Settlement.Restore)
        }

        assertEquals(EditResult.NotReady(notReady), result)
        assertNull(kept())
    }

    @Test
    fun `an amend that finds the index not ready keeps the Rejected Edit`() = runTest {
        reject()

        assertEquals(EditResult.NotReady(notReady, "e1"), amend("e1", EditResult.NotReady(notReady), Settlement.Restore))
        assertEquals("e1", kept())
    }

    @Test
    fun `an amend failing on one of its own Operations keeps the Rejected Edit`() = runTest {
        reject()

        val result = amend("e1", EditResult.OperationFailed(1, OperationFailure.BlankCode), Settlement.Restore)

        assertEquals(EditResult.OperationFailed(1, OperationFailure.BlankCode, rejectedEdit = "e1"), result)
        assertEquals("e1", kept())
    }

    @Test
    fun `an amend whose Rejected Edit no longer applies drops it`() = runTest {
        reject()
        val failed = EditResult.OperationFailed(0, OperationFailure.BlankCode, inRejected = true)

        assertEquals(failed, amend("e1", failed, Settlement.Drop))
        assertNull(kept())
    }

    @Test
    fun `an amend whose Validation times out drops the Rejected Edit`() = runTest {
        reject()
        val timedOut = EditResult.ValidationFailed(ValidationFailure.TimedOut)

        assertEquals(timedOut, amend("e1", timedOut, Settlement.Drop))
        assertNull(kept())
    }

    @Test
    fun `a written amend drops the Rejected Edit`() = runTest {
        reject()
        val written = EditResult.Written(emptyList())

        assertEquals(written, amend("e1", written, Settlement.Drop))
        assertNull(kept())
    }

    @Test
    fun `a rejected amend keeps the combined Edit under a new id in place of the one it amended`() = runTest {
        reject()

        assertEquals("e2", amend("e1", rejected, Settlement.Keep(listOf(operation, fix), emptyMap())).rejectedEdit)

        assertEquals(EditResult.NoRejectedEdit("e1", "e2"), calls.edit("e1") { error("not run") })
        calls.edit("e2") { taken ->
            assertEquals(listOf(operation, fix), taken?.operations)
            Settled(EditResult.Written(emptyList()), Settlement.Drop)
        }
    }

    @Test
    fun `a rename drops the Rejected Edit, whatever its outcome`() = runTest {
        reject()

        assertEquals("renamed", calls.rename { "renamed" })
        assertNull(kept())
    }
}
