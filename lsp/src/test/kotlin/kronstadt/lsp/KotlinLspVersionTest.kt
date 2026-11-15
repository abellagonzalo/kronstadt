package kronstadt.lsp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KotlinLspVersionTest {
    @Test
    fun `accepts the tested version as kotlin-lsp reports it`() {
        assertEquals("271.2085.0", KotlinLspVersion.TESTED)
        assertNull(KotlinLspVersion.mismatchWarning("ILS-271.2085.0"))
    }

    @Test
    fun `warns on another or a missing version`() {
        val warning = assertNotNull(KotlinLspVersion.mismatchWarning("ILS-272.1000.3"))
        assertTrue("ILS-272.1000.3" in warning && "271.2085.0" in warning, warning)
        assertNotNull(KotlinLspVersion.mismatchWarning(null))
    }
}
