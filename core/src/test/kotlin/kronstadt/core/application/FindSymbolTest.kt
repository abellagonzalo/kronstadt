package kronstadt.core.application

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.testTimeSource
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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

    @OptIn(ExperimentalCoroutinesApi::class) // testTimeSource
    @Test
    fun `waits until the index is ready, then answers fully`() = runTest {
        val intelligence = FakeCodeIntelligence(workspaceSymbols = symbols, notReadyPhase = "Indexing")
        val start = testTimeSource.markNow()
        launch {
            delay(30.seconds)
            intelligence.becomeReady()
        }
        val found = assertIs<FindSymbolResult.Found>(FindSymbol(intelligence, readinessTimeout = 90.seconds)("UserRepository"))
        assertEquals(3, found.symbols.size)
        assertEquals(30.seconds, start.elapsedNow())
    }

    @Test
    fun `fails with the phase and elapsed time when the index is not ready in time, without searching`() = runTest {
        val intelligence = FakeCodeIntelligence(workspaceSymbols = symbols, notReadyPhase = "Indexing")
        val result = FindSymbol(intelligence, readinessTimeout = 90.seconds)("UserRepository")
        assertEquals(FindSymbolResult.NotReady(phase = "Indexing", elapsed = 90.seconds), result)
        assertEquals(emptyList(), intelligence.searched)
    }

    @Test
    fun `reports no matches and blank queries`() = runTest {
        assertIs<FindSymbolResult.NoMatches>(FindSymbol(FakeCodeIntelligence())("Nope"))
        assertIs<FindSymbolResult.InvalidQuery>(FindSymbol(FakeCodeIntelligence())(" "))
    }
}
