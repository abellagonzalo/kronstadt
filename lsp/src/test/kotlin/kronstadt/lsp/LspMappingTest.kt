package kronstadt.lsp

import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolSearch
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

    @Test
    fun `a full page of workspace symbols may be incomplete, for exact names only when it ends in one`() {
        fun page(vararg names: String, padding: Int = 0): Either<List<SymbolInformation>, List<WorkspaceSymbol>> {
            val inJar = Location("jar:file:///lib.jar!/A.class", range(0, 0))
            return Either.forRight((names.toList() + List(padding) { "getOther$it" }).map { WorkspaceSymbol(it, LspSymbolKind.Function, Either.forLeft(inJar)) })
        }
        val cap = LspMapping.WORKSPACE_SYMBOL_CAP

        assertEquals(SymbolSearch.Completeness.COMPLETE, LspMapping.workspaceSymbolCompleteness(page("get", padding = cap - 2), "get"))
        assertEquals(SymbolSearch.Completeness.COMPLETE, LspMapping.workspaceSymbolCompleteness(null, "get"))
        // Full, dependency symbols counted too: names merely containing the query fill the end, so every exact one came.
        assertEquals(SymbolSearch.Completeness.EXACT_NAMES_COMPLETE, LspMapping.workspaceSymbolCompleteness(page("get", padding = cap - 1), "get"))
        assertEquals(SymbolSearch.Completeness.INCOMPLETE, LspMapping.workspaceSymbolCompleteness(page(*Array(cap) { "get" }), "get"))
        // A page ending in the name in another case may have cut the exact name off too.
        assertEquals(SymbolSearch.Completeness.INCOMPLETE, LspMapping.workspaceSymbolCompleteness(page("get", padding = cap - 2).let { Either.forRight(it.right + WorkspaceSymbol("Get", LspSymbolKind.Class, it.right.first().location)) }, "get"))
    }

    // Definition URIs as kotlin-lsp 271.2085.0 returns them (see the contract test).

    @Test
    fun `a definition in a local file is local`() {
        assertEquals(DefinitionTarget.Local(Path.of("/p/src/A.kt")), LspMapping.definitionTarget("file:///p/src/A.kt"))
    }

    @Test
    fun `a definition in a sources jar names the jar and the entry inside it`() {
        assertEquals(
            DefinitionTarget.Jar(Path.of("/cache/kotlin-stdlib-2.4.21-sources.jar"), "commonMain/kotlin/collections/Collections.kt"),
            LspMapping.definitionTarget("jar:///cache/kotlin-stdlib-2.4.21-sources.jar!/commonMain/kotlin/collections/Collections.kt"),
        )
    }

    @Test
    fun `a definition in the JDK names its module and the class file`() {
        assertEquals(
            DefinitionTarget.Jdk("java.base", "java/io/File.class"),
            LspMapping.definitionTarget("jrt:///opt/jbr/Contents/Home!/java.base/java/io/File.class"),
        )
    }

    @Test
    fun `definition URIs are decoded, and other schemes are dropped`() {
        assertEquals(
            DefinitionTarget.Jar(Path.of("/my cache/lib-sources.jar"), "a/B C.kt"),
            LspMapping.definitionTarget("jar:///my%20cache/lib-sources.jar!/a/B%20C.kt"),
        )
        assertEquals(null, LspMapping.definitionTarget("untitled:Scratch.kt"))
    }
}
