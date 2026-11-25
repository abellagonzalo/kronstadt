package kronstadt.core.application

import kronstadt.core.domain.Readiness
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import kotlin.time.Duration

/**
 * Use case behind `kt_read_symbol`: exactly the Symbol Span that a replace or delete would act on.
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
            val note = found.preferred?.let { " (${it.picked}; ${it.sharedName})" } ?: ""
            ReadSymbolResult.Found("// ${found.canonical} in ${found.analysed.shown}, L${span.firstLine + 1}-${span.lastLine + 1}$note\n${span.text}")
        }
    }
}

public sealed interface ReadSymbolResult {
    /**
     * Header (canonical path, file, 1-based line span, and, if a bare name chose a non-function over same-named functions,
     * its kind and those functions) then the raw Symbol Span, no line-number gutter.
     */
    public data class Found(val text: String) : ReadSymbolResult
    public data class LookupFailed(val failure: LookupFailure) : ReadSymbolResult

    /** Resolving without a file needs the index, which was still incomplete after waiting. */
    public data class NotReady(val readiness: Readiness.NotReady) : ReadSymbolResult
}
