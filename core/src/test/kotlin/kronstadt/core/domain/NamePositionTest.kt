package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Where Kronstadt points the code intelligence at a Symbol's name. */
class NamePositionTest {
    private fun function(name: String, selection: TextRange) =
        CodeSymbol(name, SymbolKind.FUNCTION, TextRange(Position(0, 0), Position(0, 30)), selection)

    @Test
    fun `a plain name is pointed at its first character`() {
        val document = TextDocument("fun plain(): Int = 3")
        val symbol = function("plain", TextRange(Position(0, 4), Position(0, 9)))

        assertEquals(Position(0, 4), symbol.namePosition(document))
    }

    @Test
    fun `a backticked name is pointed at the first character after its opening backtick`() {
        val document = TextDocument("fun `backticked name`(): Int = 3")
        val symbol = function("backticked name", TextRange(Position(0, 4), Position(0, 21)))

        assertEquals(Position(0, 5), symbol.namePosition(document))
    }
}
