package kronstadt.core.application

import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.port.CodeIntelligence
import kotlin.time.Duration

/**
 * Use case behind `kt_find_symbol`: locate declarations by name without knowing their file.
 *
 * Waits up to [readinessTimeout] for a complete index first: an incomplete one silently returns fewer symbols,
 * which would be indistinguishable from a real miss.
 */
public class FindSymbol(
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
) {
    public suspend operator fun invoke(query: String, kind: SymbolKind? = null, limit: Int = DEFAULT_LIMIT): FindSymbolResult {
        if (query.isBlank()) return FindSymbolResult.InvalidQuery("query must not be blank")
        val readiness = intelligence.awaitReady(readinessTimeout)
        if (readiness is Readiness.NotReady) return FindSymbolResult.NotReady(readiness.phase, readiness.elapsed)
        val matches = intelligence.workspaceSymbols(query)
            .filter { kind == null || it.kind == kind }
            // Exact name matches first, then prefix matches, then everything else the server considered relevant.
            .sortedWith(compareBy<SymbolLocation> { rank(it.name, query) }.thenBy { it.name }.thenBy { it.file })
        return when {
            matches.isEmpty() -> FindSymbolResult.NoMatches(query, kind)
            else -> FindSymbolResult.Found(matches.take(limit), truncated = matches.size > limit)
        }
    }

    private fun rank(name: String, query: String): Int = when {
        name == query -> 0
        name.equals(query, ignoreCase = true) -> 1
        name.startsWith(query, ignoreCase = true) -> 2
        else -> 3
    }

    public companion object {
        public const val DEFAULT_LIMIT: Int = 50
    }
}

public sealed interface FindSymbolResult {
    public data class Found(val symbols: List<SymbolLocation>, val truncated: Boolean) : FindSymbolResult
    public data class NoMatches(val query: String, val kind: SymbolKind?) : FindSymbolResult
    public data class InvalidQuery(val reason: String) : FindSymbolResult

    /** The index was still incomplete after waiting [elapsed]; nothing was searched. */
    public data class NotReady(val phase: String, val elapsed: Duration) : FindSymbolResult
}
