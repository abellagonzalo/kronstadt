package kronstadt.core.application

import kronstadt.core.domain.PreferredOverFunctions
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import kotlin.time.Duration

/**
 * Use case behind reading a Symbol: exactly the Symbol Span that a replace or delete would act on.
 *
 * With a file it is syntax-only, answering from the file's `documentSymbol` tree without waiting for the project
 * index. Without one it resolves project-wide, which waits up to [readinessTimeout] for the index.
 */
public class ReadSymbol(
    files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
) {
    private val resolver = SymbolResolver(files, intelligence)

    /** Reads [symbol] in the file at [path]; without a [path], wherever in the project it is. */
    public suspend operator fun invoke(path: String?, symbol: String): ReadSymbolResult {
        val parsed = SymbolResolver.parse(path, symbol).getOrElse { return ReadSymbolResult.LookupFailed(it) }
        if (path == null) {
            val readiness = intelligence.awaitReady(readinessTimeout)
            if (readiness is Readiness.NotReady) return ReadSymbolResult.NotReady(readiness)
        }
        return intelligence.session { session ->
            val found = resolver.resolve(session, parsed).getOrElse { return@session ReadSymbolResult.LookupFailed(it) }
            val span = resolver.span(found).getOrElse { return@session ReadSymbolResult.LookupFailed(it) }
            ReadSymbolResult.Found(found.canonical, found.analysed.shown, span, found.preferred)
        }
    }
}

public sealed interface ReadSymbolResult {
    /**
     * The Symbol at canonical Symbol Path [symbol] in [file] (as shown), its Symbol Span, and, if a bare name chose a
     * non-function over same-named functions, which ones it was [preferred] over.
     */
    public data class Found(
        val symbol: String,
        val file: String,
        val span: SymbolSpan,
        val preferred: PreferredOverFunctions? = null,
    ) : ReadSymbolResult
    public data class LookupFailed(val failure: LookupFailure) : ReadSymbolResult

    /** Resolving without a file needs the index, which was still incomplete after waiting. */
    public data class NotReady(val readiness: Readiness.NotReady) : ReadSymbolResult
}
