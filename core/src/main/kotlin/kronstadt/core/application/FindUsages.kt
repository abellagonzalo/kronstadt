package kronstadt.core.application

import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolPath
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import kotlin.time.Duration

/**
 * Use case behind `kt_find_usages`: every Usage of one Symbol in project source, grouped by file and then by the
 * canonical Symbol Path that contains it, so each group can be passed straight to `kt_read_symbol` or `kt_edit`.
 */
public class FindUsages(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
) {
    private val resolver = SymbolResolver(files, intelligence, readinessTimeout)

    /** Finds every Usage of [symbol], resolved project-wide; with a [file], only the Usages in that file. */
    public suspend operator fun invoke(symbol: String, file: String? = null): FindUsagesResult {
        val symbolPath = SymbolPath.parse(symbol).getOrElse { return FindUsagesResult.InvalidPath(symbol, it.message.orEmpty()) }
        val fileFilter = file?.let { path ->
            val resolved = files.resolve(path) ?: return FindUsagesResult.OutsideProject(path)
            if (!AnalysedFile.isKotlin(resolved)) return FindUsagesResult.NotKotlin(path)
            files.read(resolved) ?: return FindUsagesResult.FileNotFound(path)
            resolved
        }
        val declaration = when (val resolution = resolver.resolve(null, symbolPath)) {
            is SymbolResolver.Resolution.Found -> resolution
            is SymbolResolver.Resolution.Ambiguous -> return FindUsagesResult.Ambiguous(symbol, resolution.candidates)
            is SymbolResolver.Resolution.NotFound -> return FindUsagesResult.NotFound(symbol, resolution.suggestions)
            is SymbolResolver.Resolution.NotReady -> return FindUsagesResult.NotReady(resolution.phase, resolution.elapsed)
            // Only resolving in a given file reports these.
            is SymbolResolver.Resolution.FileNotFound, is SymbolResolver.Resolution.NotKotlin,
            is SymbolResolver.Resolution.OutsideProject -> error("project-wide resolution reported $resolution")
        }
        val usagesAndImports = try {
            intelligence.references(declaration.file, declaration.document, declaration.node.symbol.selectionRange.start)
        } finally {
            intelligence.closeDocument(declaration.file)
        }.filter { files.resolve(it.file.toString()) == it.file && (fileFilter == null || it.file == fileFilter) }
        var imports = 0
        // Only text is needed to tell imports apart; trees are fetched for the files shown.
        val withUsages = usagesAndImports.groupBy { it.file }.entries
            .sortedWith(compareBy({ isTest(files.display(it.key)) }, { files.display(it.key) }))
            .mapNotNull { (file, inFile) ->
                val document = files.read(file) ?: return@mapNotNull null
                val (fileImports, usages) = inFile.sortedBy { it.range.start }
                    .partition { document.line(it.range.start.line).trimStart().startsWith("import ") }
                imports += fileImports.size
                if (usages.isEmpty()) null else file to usages
            }
        if (withUsages.isEmpty()) {
            val searched = if (fileFilter != null) 1 else files.kotlinFiles().size
            return FindUsagesResult.NoUsages(declaration.tree.canonicalPath(declaration.node).toString(), searched, imports)
        }
        // Whole files only; the first is shown even if it alone exceeds the cap.
        var budget = MAX_USAGES
        val shown = withUsages.takeWhile { (_, usages) ->
            val fits = usages.size <= budget || budget == MAX_USAGES
            budget -= usages.size
            fits
        }
        val omitted = withUsages.drop(shown.size)
        val text = buildString {
            append(count(withUsages.sumOf { (_, usages) -> usages.size }, "usage")).append(" in ").append(count(withUsages.size, "file"))
            if (imports > 0) append(" (+").append(count(imports, "import")).append(')')
            shown.forEach { (file, usages) ->
                val analysed = AnalysedFile.of(files, intelligence, file) ?: return@forEach
                append('\n').append(files.display(file))
                usages.groupBy { usage ->
                    analysed.tree.nodes.lastOrNull { usage.range.start in it.symbol.range }
                }.forEach { (node, inNode) ->
                    append("\n  ").append(node?.let { analysed.tree.canonicalPath(it).toString() } ?: FILE_LEVEL)
                    inNode.map { it.range.start.line }.distinct().forEach { line ->
                        append("\n    L${line + 1}: ").append(truncate(analysed.document.line(line).trim()))
                    }
                }
            }
            if (omitted.isNotEmpty()) {
                append("\n… ").append(count(omitted.sumOf { (_, usages) -> usages.size }, "more usage"))
                append(" in ").append(count(omitted.size, "file")).append(": ")
                append(omitted.joinToString(", ") { (file, _) -> files.display(file) })
            }
        }
        return FindUsagesResult.Found(text)
    }

    /** A file in a test source set: `src/test`, or any `src/<name>` that starts with `test` or ends with `Test`. */
    private fun isTest(path: String): Boolean {
        val segments = path.split('/')
        return segments.zipWithNext().any { (parent, sourceSet) ->
            parent == "src" && (sourceSet.startsWith("test") || sourceSet.endsWith("Test"))
        }
    }

    private fun truncate(source: String) = if (source.length <= MAX_LINE) source else source.take(MAX_LINE - 1) + "…"

    private fun count(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    public companion object {
        /** Usages shown at most, in whole files. */
        public const val MAX_USAGES: Int = 100
        private const val MAX_LINE = 120
        private const val FILE_LEVEL = "(file level)"
    }
}

public sealed interface FindUsagesResult {
    /** The rendered Usages: a summary line, then each file and the Symbol Paths in it that hold Usages. */
    public data class Found(val text: String) : FindUsagesResult

    /** [symbol] (its canonical path) has no Usages in any of [searchedFiles] files, other than [imports] imports. */
    public data class NoUsages(val symbol: String, val searchedFiles: Int, val imports: Int = 0) : FindUsagesResult
    public data class InvalidPath(val symbol: String, val reason: String) : FindUsagesResult

    /** Resolved project-wide, so each candidate names its own file. */
    public data class Ambiguous(val symbol: String, val candidates: List<SymbolCandidate>) : FindUsagesResult
    public data class NotFound(val symbol: String, val suggestions: List<SymbolCandidate>) : FindUsagesResult

    /** The index was still incomplete after waiting [elapsed]. */
    public data class NotReady(val phase: String, val elapsed: Duration) : FindUsagesResult
    public data class FileNotFound(val path: String) : FindUsagesResult
    public data class NotKotlin(val path: String) : FindUsagesResult
    public data class OutsideProject(val path: String) : FindUsagesResult
}
