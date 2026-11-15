package kronstadt.lsp

import kronstadt.core.domain.SymbolKind
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.eclipse.lsp4j.SymbolKind as LspSymbolKind

class LspMappingTest {
    private fun range(a: Int, b: Int) = Range(Position(a, 0), Position(b, 1))

    @Test
    fun `maps hierarchical document symbols with children`() {
        val method = DocumentSymbol("find", LspSymbolKind.Method, range(2, 4), range(2, 2), "(String): Repo?")
        val clazz = DocumentSymbol("Repo", LspSymbolKind.Class, range(0, 5), range(0, 0), null, listOf(method))
        val result = LspMapping.documentSymbols(listOf(Either.forRight(clazz)))

        val mapped = result.single()
        assertEquals(SymbolKind.CLASS, mapped.kind)
        assertEquals(SymbolKind.FUNCTION, mapped.children.single().kind)
        assertEquals(2, mapped.children.single().range.start.line)
        assertEquals("(String): Repo?", mapped.children.single().detail)
    }

    @Test
    fun `falls back gracefully for flat symbol information`() {
        val info = SymbolInformation("Repo", LspSymbolKind.Interface, Location("file:///p/Repo.kt", range(0, 3)))
        val mapped = LspMapping.documentSymbols(listOf(Either.forLeft(info))).single()
        assertEquals(SymbolKind.INTERFACE, mapped.kind)
        assertEquals(mapped.range, mapped.selectionRange)
    }

    @Test
    fun `keeps only local workspace symbols that carry a range`() {
        val local = WorkspaceSymbol("Repo", LspSymbolKind.Class, Either.forLeft(Location("file:///p/Repo.kt", range(1, 2))))
        val inJar = WorkspaceSymbol("List", LspSymbolKind.Interface, Either.forLeft(Location("jar:file:///lib.jar!/List.class", range(0, 0))))
        val unresolved = WorkspaceSymbol("Lazy", LspSymbolKind.Class, Either.forRight(WorkspaceSymbolLocation("file:///p/Lazy.kt")))

        val mapped = LspMapping.workspaceSymbols(Either.forRight(listOf(local, inJar, unresolved)))
        assertEquals(listOf("Repo"), mapped.map { it.name })
        assertEquals(Path.of("/p/Repo.kt"), mapped.single().file)
    }
}
