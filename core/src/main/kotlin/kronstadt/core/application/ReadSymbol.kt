package kronstadt.core.application

import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.SymbolSpan
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
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
) {
    private val resolver = SymbolResolver(files, intelligence, readinessTimeout)

    /** Reads [symbol] in the file at [path]; without a [path], wherever in the project it is. */
    public suspend operator fun invoke(path: String?, symbol: String): ReadSymbolResult {
        val symbolPath = SymbolPath.parse(symbol).getOrElse { return ReadSymbolResult.InvalidPath(symbol, it.message.orEmpty()) }
        if (path != null && symbolPath.packageName != null) {
            return ReadSymbolResult.InvalidPath(symbol, "a package prefix is only for paths without a file; drop it or the file")
        }
        val found = when (val resolution = intelligence.session { resolver.resolve(it, path, symbolPath) }) {
            is SymbolResolver.Resolution.Found -> resolution
            is SymbolResolver.Resolution.Ambiguous -> return ReadSymbolResult.Ambiguous(symbol, path?.let(::shown), resolution.candidates)
            is SymbolResolver.Resolution.NotFound -> return ReadSymbolResult.NotFound(symbol, path?.let(::shown), resolution.suggestions)
            is SymbolResolver.Resolution.NotReady -> return ReadSymbolResult.NotReady(resolution.phase, resolution.elapsed)
            is SymbolResolver.Resolution.FileNotFound -> return ReadSymbolResult.FileNotFound(resolution.path)
            is SymbolResolver.Resolution.NotKotlin -> return ReadSymbolResult.NotKotlin(resolution.path)
            is SymbolResolver.Resolution.OutsideProject -> return ReadSymbolResult.OutsideProject(resolution.path)
        }
        val shown = files.display(found.file)
        val canonical = found.tree.canonicalPath(found.node).toString()
        return when (val computation = SymbolSpan.of(found.document, found.node.symbol)) {
            is SymbolSpan.Computation.Computed -> with(computation.span) {
                ReadSymbolResult.Found("// $canonical in $shown, L${firstLine + 1}-${lastLine + 1}\n$text")
            }
            SymbolSpan.Computation.OutOfSync -> ReadSymbolResult.OutOfSync(canonical, shown)
            is SymbolSpan.Computation.SharedLine -> ReadSymbolResult.SharedLine(canonical, shown, computation.line + 1)
        }
    }

    /** The file as the agent should see it, once [SymbolResolver] has accepted [path]. */
    private fun shown(path: String): String = files.resolve(path)?.let(files::display) ?: path
}

public sealed interface ReadSymbolResult {
    /** Header (canonical path, file, 1-based line span) then the raw Symbol Span, no line-number gutter. */
    public data class Found(val text: String) : ReadSymbolResult
    public data class InvalidPath(val symbol: String, val reason: String) : ReadSymbolResult
    /** [file] is null when the path was resolved project-wide; each candidate names its own file. */
    public data class Ambiguous(val symbol: String, val file: String?, val candidates: List<SymbolCandidate>) : ReadSymbolResult
    public data class NotFound(val symbol: String, val file: String?, val suggestions: List<SymbolCandidate>) : ReadSymbolResult

    /** Resolving without a file needs the index, which was still incomplete after waiting [elapsed]. */
    public data class NotReady(val phase: String, val elapsed: Duration) : ReadSymbolResult

    /** The text at the Symbol's name range isn't its name, so its range can't be trusted. */
    public data class OutOfSync(val symbol: String, val file: String) : ReadSymbolResult

    /** Something other than whitespace shares 1-based [line] with the Symbol. */
    public data class SharedLine(val symbol: String, val file: String, val line: Int) : ReadSymbolResult
    public data class FileNotFound(val path: String) : ReadSymbolResult
    public data class NotKotlin(val path: String) : ReadSymbolResult
    public data class OutsideProject(val path: String) : ReadSymbolResult
}
