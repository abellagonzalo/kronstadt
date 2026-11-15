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
    private val files = FakeFileStore(
        root,
        mapOf("src/A.kt" to "class A {\n}\n", "build.gradle.kts" to "plugins {}\n", "README.md" to "# hi"),
    )
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
    fun `answers while kotlin-lsp is still indexing and closes the document it opened`() = runTest {
        val indexing = FakeCodeIntelligence(documentSymbols = listOf(symbol), notReadyPhase = "Indexing")

        assertIs<ReadStructureResult.Structure>(ReadStructure(files, indexing)("src/A.kt"))
        assertEquals(emptySet(), indexing.openDocuments)
    }

    @Test
    fun `a file with no declarations says so instead of an empty structure`() = runTest {
        val empty = FakeCodeIntelligence(documentSymbols = emptyList())

        val result = ReadStructure(files, empty)("build.gradle.kts")

        assertEquals(ReadStructureResult.NoDeclarations("build.gradle.kts"), result)
        assertEquals(emptySet(), empty.openDocuments)
    }

    @Test
    fun `reports missing files, non-Kotlin files, and paths escaping the project`() = runTest {
        assertIs<ReadStructureResult.FileNotFound>(readStructure("src/Missing.kt"))
        assertIs<ReadStructureResult.NotKotlin>(readStructure("README.md"))
        assertIs<ReadStructureResult.OutsideProject>(readStructure("../other/B.kt"))
    }
}
