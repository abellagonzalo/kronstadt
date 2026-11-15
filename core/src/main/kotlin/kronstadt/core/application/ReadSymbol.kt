package kronstadt.core.application

import kronstadt.core.domain.SymbolPath
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.SymbolTree
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore

/**
 * Use case behind `kt_read_symbol`: exactly the Symbol Span that a replace or delete would act on.
 *
 * Syntax-only: answers from the file's `documentSymbol` tree without waiting for the project index.
 */
public class ReadSymbol(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
) {
    public suspend operator fun invoke(path: String, symbol: String): ReadSymbolResult {
        val symbolPath = SymbolPath.parse(symbol).getOrElse { return ReadSymbolResult.InvalidPath(symbol, it.message.orEmpty()) }
        val file = files.resolve(path) ?: return ReadSymbolResult.OutsideProject(path)
        if (!file.fileName.toString().let { it.endsWith(".kt") || it.endsWith(".kts") }) {
            return ReadSymbolResult.NotKotlin(path)
        }
        val document = files.read(file) ?: return ReadSymbolResult.FileNotFound(path)
        val shown = files.display(file)
        val tree = try {
            SymbolTree(intelligence.documentSymbols(file, document))
        } finally {
            intelligence.closeDocument(file)
        }
        val node = when (val resolution = tree.resolve(symbolPath)) {
            is SymbolTree.Resolution.Found -> resolution.node
            is SymbolTree.Resolution.Ambiguous ->
                return ReadSymbolResult.Ambiguous(symbol, shown, resolution.candidates.map { tree.canonicalPath(it).toString() })
            is SymbolTree.Resolution.NotFound ->
                return ReadSymbolResult.NotFound(symbol, shown, resolution.suggestions.map { tree.canonicalPath(it).toString() })
        }
        val canonical = tree.canonicalPath(node).toString()
        return when (val computation = SymbolSpan.of(document, node.symbol)) {
            is SymbolSpan.Computation.Computed -> with(computation.span) {
                ReadSymbolResult.Found("// $canonical in $shown, L${firstLine + 1}-${lastLine + 1}\n$text")
            }
            SymbolSpan.Computation.OutOfSync -> ReadSymbolResult.OutOfSync(canonical, shown)
            is SymbolSpan.Computation.SharedLine -> ReadSymbolResult.SharedLine(canonical, shown, computation.line + 1)
        }
    }
}

public sealed interface ReadSymbolResult {
    /** Header (canonical path, file, 1-based line span) then the raw Symbol Span, no line-number gutter. */
    public data class Found(val text: String) : ReadSymbolResult
    public data class InvalidPath(val symbol: String, val reason: String) : ReadSymbolResult
    public data class Ambiguous(val symbol: String, val file: String, val candidates: List<String>) : ReadSymbolResult
    public data class NotFound(val symbol: String, val file: String, val suggestions: List<String>) : ReadSymbolResult

    /** The text at the Symbol's name range isn't its name, so its range can't be trusted. */
    public data class OutOfSync(val symbol: String, val file: String) : ReadSymbolResult

    /** Something other than whitespace shares 1-based [line] with the Symbol. */
    public data class SharedLine(val symbol: String, val file: String, val line: Int) : ReadSymbolResult
    public data class FileNotFound(val path: String) : ReadSymbolResult
    public data class NotKotlin(val path: String) : ReadSymbolResult
    public data class OutsideProject(val path: String) : ReadSymbolResult
}
