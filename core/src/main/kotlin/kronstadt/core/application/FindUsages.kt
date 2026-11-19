package kronstadt.core.application

import kronstadt.core.domain.Readiness
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Use case behind `kt_find_usages`: every Usage of one Symbol in project source, grouped by file and then by the
 * canonical Symbol Path that contains it, so each group can be passed straight to `kt_read_symbol` or `kt_edit`.
 */
public class FindUsages(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
) {
    private val resolver = SymbolResolver(files, intelligence)

    /** Finds every Usage of [symbol], resolved project-wide; with a [file], only the Usages in that file. */
    public suspend operator fun invoke(symbol: String, file: String? = null): FindUsagesResult {
        // [file] filters the Usages; the Symbol itself is always resolved project-wide.
        val parsed = SymbolResolver.parse(null, symbol).getOrElse { return FindUsagesResult.LookupFailed(it) }
        val fileFilter = file?.let { path -> files.classify(path).getOrElse { return FindUsagesResult.LookupFailed(it) } }
        val readiness = intelligence.awaitReady(readinessTimeout)
        if (readiness is Readiness.NotReady) return FindUsagesResult.NotReady(readiness)
        return intelligence.session { session -> usages(session, parsed, fileFilter) }
    }

    /** Resolves the Symbol and lists its Usages, analysing every file in the tool call's one [session]. */
    private suspend fun usages(session: AnalysisSession, parsed: ParsedLookup, fileFilter: Path?): FindUsagesResult {
        val declaration = resolver.resolve(session, parsed).getOrElse { return FindUsagesResult.LookupFailed(it) }
        val usagesAndImports = session.references(declaration.file, declaration.document, declaration.node.symbol.selectionRange.start)
            .filter { files.resolve(it.file.toString()) == it.file && (fileFilter == null || it.file == fileFilter) }
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
            return FindUsagesResult.NoUsages(declaration.canonical, searched, imports)
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
                val analysed = AnalysedFile.of(files, session, file) ?: return@forEach
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

    /** The Symbol, resolved project-wide, or the file to list Usages in, is not one. */
    public data class LookupFailed(val failure: LookupFailure) : FindUsagesResult

    /** The index was still incomplete after waiting. */
    public data class NotReady(val readiness: Readiness.NotReady) : FindUsagesResult
}
