package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FindSymbolTest {
    private fun loc(name: String, kind: SymbolKind) =
        SymbolLocation(name, kind, Path.of("/project/$name.kt"), TextRange(Position(0, 0), Position(0, 1)))

    private val symbols = listOf(
        loc("UserRepositoryImpl", SymbolKind.CLASS),
        loc("findUserRepository", SymbolKind.FUNCTION),
        loc("UserRepository", SymbolKind.INTERFACE),
    )

    @Test
    fun `ranks exact matches first, then prefix matches`() = runTest {
        val found = assertIs<FindSymbolResult.Found>(FindSymbol(FakeCodeIntelligence(workspaceSymbols = symbols))("UserRepository"))
        assertEquals(listOf("UserRepository", "UserRepositoryImpl", "findUserRepository"), found.symbols.map { it.name })
    }

    @Test
    fun `filters by kind and truncates`() = runTest {
        val findSymbol = FindSymbol(FakeCodeIntelligence(workspaceSymbols = symbols))
        val classes = assertIs<FindSymbolResult.Found>(findSymbol("User", SymbolKind.CLASS))
        assertEquals(listOf("UserRepositoryImpl"), classes.symbols.map { it.name })

        val limited = assertIs<FindSymbolResult.Found>(findSymbol("User", limit = 1))
        assertTrue(limited.truncated)
    }

    @Test
    fun `reports no matches and blank queries`() = runTest {
        assertIs<FindSymbolResult.NoMatches>(FindSymbol(FakeCodeIntelligence())("Nope"))
        assertIs<FindSymbolResult.InvalidQuery>(FindSymbol(FakeCodeIntelligence())(" "))
    }
}
