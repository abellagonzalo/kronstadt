package kronstadt.core.application

import kronstadt.core.domain.Readiness
import kronstadt.core.domain.namePosition
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Use case behind finding Usages: every Usage of one Symbol in project source, grouped by file and then by the
 * canonical Symbol Path that contains it, so each group can be passed straight to a read or an Edit.
 */
public class FindUsages(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
) {
    private val resolver = SymbolResolver(files, intelligence)

    /**
     * Finds every Usage of [symbol], declared in [declaredIn] or, without it, resolved project-wide; with a [file], only
     * the Usages in that file.
     */
    public suspend operator fun invoke(symbol: String, file: String? = null, declaredIn: String? = null): FindUsagesResult {
        // [declaredIn] is where the Symbol is resolved; [file] only filters the Usages.
        val parsed = SymbolResolver.parse(declaredIn, symbol).getOrElse { return FindUsagesResult.LookupFailed(it) }
        // Both files are checked before waiting for the index, each failure naming its argument.
        declaredIn?.let { path ->
            files.classify(path).getOrElse { return FindUsagesResult.LookupFailed(it, FindUsagesResult.FileArgument.DECLARED_IN) }
        }
        val fileFilter = file?.let { path ->
            files.classify(path).getOrElse { return FindUsagesResult.LookupFailed(it, FindUsagesResult.FileArgument.FILE) }
        }
        val readiness = intelligence.awaitReady(readinessTimeout)
        if (readiness is Readiness.NotReady) return FindUsagesResult.NotReady(readiness)
        return intelligence.session { session -> usages(session, parsed, fileFilter) }
    }

    /** Resolves the Symbol and lists its Usages, analysing every file in the tool call's one [session]. */
    private suspend fun usages(session: AnalysisSession, parsed: ParsedLookup, fileFilter: Path?): FindUsagesResult {
        val declaration = resolver.resolve(session, parsed).getOrElse { return FindUsagesResult.LookupFailed(it) }
        val usagesAndImports = session.references(declaration.analysed.file, declaration.analysed.document, declaration.node.symbol.namePosition(declaration.analysed.document))
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
        val listed = shown.mapNotNull { (file, usages) ->
            val analysed = session.analyse(files, file) ?: return@mapNotNull null
            val inSymbols = usages.groupBy { usage ->
                analysed.nodes.lastOrNull { usage.range.start in it.symbol.range }
            }.map { (node, inNode) ->
                UsagesInSymbol(
                    node?.let { analysed.path(it).toString() },
                    inNode.map { it.range.start.line }.distinct().map { line -> UsageLine(line + 1, analysed.document.line(line).trim()) },
                )
            }
            UsagesInFile(analysed.shown, inSymbols)
        }
        return FindUsagesResult.Found(
            usageCount = withUsages.sumOf { (_, usages) -> usages.size },
            fileCount = withUsages.size,
            importCount = imports,
            files = listed,
            leftOut = omitted.map { (file, _) -> files.display(file) },
            leftOutUsageCount = omitted.sumOf { (_, usages) -> usages.size },
        )
    }

    /** A file in a test source set: `src/test`, or any `src/<name>` that starts with `test` or ends with `Test`. */
    private fun isTest(path: String): Boolean {
        val segments = path.split('/')
        return segments.zipWithNext().any { (parent, sourceSet) ->
            parent == "src" && (sourceSet.startsWith("test") || sourceSet.endsWith("Test"))
        }
    }

    public companion object {
        /** Usages shown at most, in whole files. */
        public const val MAX_USAGES: Int = 100
    }
}

public sealed interface FindUsagesResult {
    /**
     * [usageCount] Usages in [fileCount] files, besides [importCount] imports. [files] lists those within the cap of
     * [FindUsages.MAX_USAGES], whole files only; [leftOut] names the files beyond it, which hold [leftOutUsageCount] Usages.
     */
    public data class Found(
        val usageCount: Int,
        val fileCount: Int,
        val importCount: Int,
        val files: List<UsagesInFile>,
        val leftOut: List<String> = emptyList(),
        val leftOutUsageCount: Int = 0,
    ) : FindUsagesResult

    /** [symbol] (its canonical path) has no Usages in any of [searchedFiles] files, other than [imports] imports. */
    public data class NoUsages(val symbol: String, val searchedFiles: Int, val imports: Int = 0) : FindUsagesResult

    /**
     * The Symbol, or a file to resolve it in or list Usages in, is not one. A file check's failure names the [argument] the
     * file was passed as, so the agent knows which path is wrong; it is null for the Symbol Path's own failures.
     */
    public data class LookupFailed(val failure: LookupFailure, val argument: FileArgument? = null) : FindUsagesResult

    /** The two files the use case takes: the one [DECLARED_IN] that declares the Symbol, and the [FILE] to list Usages in. */
    public enum class FileArgument { DECLARED_IN, FILE }

    /** The index was still incomplete after waiting. */
    public data class NotReady(val readiness: Readiness.NotReady) : FindUsagesResult
}

/** The Usages in one [file] (as shown), grouped by the Symbol that holds them, in source order. */
public data class UsagesInFile(val file: String, val inSymbols: List<UsagesInSymbol>)

/** The [lines] holding Usages inside the Symbol at canonical Symbol Path [symbol]; `null` is outside any Symbol. */
public data class UsagesInSymbol(val symbol: String?, val lines: List<UsageLine>)

/** A 1-based [line] holding one or more Usages, and its [source], trimmed but not cut. */
public data class UsageLine(val line: Int, val source: String)
