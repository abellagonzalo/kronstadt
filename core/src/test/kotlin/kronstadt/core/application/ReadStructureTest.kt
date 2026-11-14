package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReadStructureTest {
    private val root = Path.of("/project")
    private val files = FakeFileStore(root, mapOf("src/A.kt" to "class A {\n}\n", "README.md" to "# hi"))
    private val symbol = CodeSymbol(
        "A", SymbolKind.CLASS,
        TextRange(Position(0, 0), Position(1, 1)), TextRange(Position(0, 6), Position(0, 7)),
    )
    private val intelligence = FakeCodeIntelligence(documentSymbols = listOf(symbol))
    private val readStructure = ReadStructure(files, intelligence)

    @Test
    fun `renders the structure and analyses the content it read`() = runTest {
        val result = assertIs<ReadStructureResult.Structure>(readStructure("src/A.kt", xml = false))
        assertEquals("class A  // L1-2", result.text)
        assertEquals("class A {\n}\n", intelligence.analysed.single().second.text)
    }

    @Test
    fun `reports missing files, non-Kotlin files, and paths escaping the project`() = runTest {
        assertIs<ReadStructureResult.FileNotFound>(readStructure("src/Missing.kt"))
        assertIs<ReadStructureResult.NotKotlin>(readStructure("README.md"))
        assertIs<ReadStructureResult.OutsideProject>(readStructure("../other/B.kt"))
    }
}
