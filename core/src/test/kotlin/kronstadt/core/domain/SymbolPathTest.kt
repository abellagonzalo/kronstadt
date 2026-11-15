package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolPathTest {
    private fun parse(text: String) = SymbolPath.parse(text).getOrThrow()

    @Test
    fun `a dotted first segment is a package prefix`() {
        assertEquals(
            SymbolPath(listOf(SymbolPath.Segment("Name")), anchored = true, packageName = "a.b"),
            parse("a.b/Name"),
        )
    }

    @Test
    fun `a single-word first segment is a declaration name, never a package`() {
        assertEquals(
            SymbolPath(listOf(SymbolPath.Segment("pkg"), SymbolPath.Segment("Name")), anchored = false, packageName = null),
            parse("pkg/Name"),
        )
    }
}
