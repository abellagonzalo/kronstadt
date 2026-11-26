package kronstadt.core.application

import kronstadt.core.domain.AnalysedFile
import kronstadt.core.domain.Definition
import kronstadt.core.domain.DeclarationHead
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextRange
import kronstadt.core.domain.packageName
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import kotlin.time.Duration

/**
 * Use case behind `kt_get_definition`: where a name used inside a Symbol is declared, and that declaration's contract
 * (its KDoc and declaration, without the body), found by text within the Symbol Span so the agent never computes positions.
 */
public class GetDefinition(
    private val files: FileStore,
    private val intelligence: CodeIntelligence,
    private val readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
) {
    private val resolver = SymbolResolver(files, intelligence)

    /** Resolves [name] where it occurs in [symbol] (in [file], else resolved project-wide). */
    public suspend operator fun invoke(symbol: String, name: String, at: String? = null, file: String? = null): GetDefinitionResult {
        val parsed = SymbolResolver.parse(file, symbol).getOrElse { return GetDefinitionResult.LookupFailed(it) }
        // Definitions come from the index even when the Symbol's file is given.
        val readiness = intelligence.awaitReady(readinessTimeout)
        if (readiness is Readiness.NotReady) return GetDefinitionResult.NotReady(readiness)
        return intelligence.session { session -> definitions(session, parsed, name, at) }
    }

    /** Resolves the Symbol, then [name] in it, analysing every file in the tool call's one [session]. */
    private suspend fun definitions(session: AnalysisSession, parsed: ParsedLookup, name: String, at: String?): GetDefinitionResult {
        val found = resolver.resolve(session, parsed).getOrElse { return GetDefinitionResult.LookupFailed(it) }
        val analysed = found.analysed
        val document = analysed.document
        val lines = analysed.listedLines(found.node)
        val span = document.offsetOf(Position(lines.first, 0)) until document.offsetOf(Position(lines.last, Int.MAX_VALUE))
        // The name is looked up inside the `at` snippet when there is one, else inside the whole span.
        val scope = if (at == null) span else when (val snippet = Snippet.find(document, at, span)) {
            is Snippet.Match -> snippet.range
            is Snippet.Miss -> return GetDefinitionResult.AtNotExactlyOnce(found.canonical, analysed.shown, snippet)
        }
        // Searched in the whole text, so the identifier boundaries see past the scope's ends.
        val names = identifier(name).findAll(document.text, scope.first).map { it.range }.takeWhile { it.last <= scope.last }.toList()
        val nameRange = names.singleOrNull()
            ?: return GetDefinitionResult.NameNotExactlyOnce(name, found.canonical, analysed.shown, SourceLine.startingAt(document, names))
        val position = document.positionOf(nameRange.first)
        val definitions = session.definition(analysed.file, document, position)
        if (definitions.isEmpty()) {
            val range = TextRange(position, document.positionOf(nameRange.last + 1))
            val diagnostic = session.diagnostics(analysed.file, document).firstOrNull { it.range.start < range.end && range.start < it.range.end }
            return GetDefinitionResult.Unresolved(
                name,
                found.canonical,
                position.line + 1,
                diagnostic?.let { d -> d.code?.let { "$it: ${d.message}" } ?: d.message },
            )
        }
        val text = definitions.map { definition ->
            when (definition) {
                is Definition.InSource -> sourceTarget(session, definition, name)
                is Definition.InLibrary -> libraryTarget(definition, name)
            }
        }.joinToString("\n\n")
        return GetDefinitionResult.Found(text)
    }

    /** [name] as a whole identifier, not part of a longer one; backticks optional. */
    private fun identifier(name: String): Regex {
        val bare = name.removeSurrounding("`")
        return Regex("""(?<![\p{L}\p{N}_`])`?""" + Regex.escape(bare) + """`?(?![\p{L}\p{N}_`])""")
    }

    /**
     * A declaration in a source file: a project Symbol, a local, or something in a file Kronstadt can't address (not
     * Kotlin, or outside the project), shown with the line it is on. [name] stands in for a name that can't be read.
     */
    private suspend fun sourceTarget(session: AnalysisSession, definition: Definition.InSource, name: String): String {
        val location = definition.location
        val file = location.file
        val line = location.range.start.line
        if (files.resolve(file.toString()) != file) return "// $name in $file, L${line + 1} (outside the project: read-only, not addressable)"
        val shown = files.display(file)
        if (!AnalysedFile.isKotlin(file)) {
            val document = files.read(file)?.takeIf { line < it.lineCount }
            return "// ${document?.slice(location.range) ?: name} in $shown, L${line + 1} (not Kotlin: read-only, not addressable)" +
                (document?.let { "\n" + it.line(line).trim() } ?: "")
        }
        val analysed = session.analyse(files, file) ?: return "// $name in $shown, L${line + 1} (file not found)"
        val node = analysed.nodes.lastOrNull { it.symbol.selectionRange.start == location.range.start }
            ?: return local(analysed, location.range)
        val listing = analysed.listing(node)
        val canonical = listing.path.toString()
        return "// $canonical in $shown, L${listing.firstLine}-${listing.lastLine}\n" +
            DeclarationHead.of(analysed.document, node.symbol, analysed.listedLines(node).first) + "\n" +
            "// Body omitted. Full source: kt_read_symbol(symbol=\"$canonical\", file=\"$shown\")"
    }

    /**
     * A library or JDK declaration, which no tool can edit. With sources: its fully qualified name, then its KDoc and
     * declaration without the body. Without: the class it is in, from the class file's path.
     */
    private fun libraryTarget(definition: Definition.InLibrary, name: String): String {
        val where = "${definition.origin}!/${definition.entry} (library: read-only, not addressable)"
        val source = definition.source
        val node = source?.let { SymbolTree(it.symbols).nodes.lastOrNull { node -> node.symbol.selectionRange.start == definition.range.start } }
        if (source == null || node == null) {
            val className = definition.entry.removeSuffix(".class").replace('/', '.').replace('$', '.')
            val member = if (className.substringAfterLast('.') == name) "" else "$name in "
            return "// $member$className, in ${definition.origin} (library: read-only, not addressable; no sources, so no KDoc or declaration)"
        }
        val fqName = (listOf(source.document.packageName) + (node.ancestors + node.symbol).map { it.name })
            .filter { it.isNotEmpty() }.joinToString(".")
        val (first, _) = SymbolSpan.lines(source.document, node.symbol)
        return "// $fqName in $where\n" + DeclarationHead.of(source.document, node.symbol, first)
    }

    /** A declaration no Symbol Path addresses (a local variable, parameter or local function): where it is. */
    private fun local(analysed: AnalysedFile, range: TextRange): String {
        val name = analysed.document.slice(range)
        val enclosing = analysed.nodes.lastOrNull { range.start in it.symbol.range }
            ?.let { analysed.path(it).toString() } ?: FILE_LEVEL
        val line = range.start.line
        return "// $name is local to $enclosing in ${analysed.shown}, not addressable; declared at L${line + 1}\n" +
            analysed.document.line(line).trim()
    }

    private companion object {
        const val FILE_LEVEL = "(file level)"
    }
}

public sealed interface GetDefinitionResult {
    /** Each definition as its own block, in the code intelligence's order. */
    public data class Found(val text: String) : GetDefinitionResult
    public data class LookupFailed(val failure: LookupFailure) : GetDefinitionResult

    /** The `at` Snippet doesn't occur exactly once in [symbol]'s span in [file]: [miss] says how often and where. */
    public data class AtNotExactlyOnce(val symbol: String, val file: String, val miss: Snippet.Miss) : GetDefinitionResult

    /**
     * [name] doesn't occur exactly once as a whole identifier in [symbol]'s span in [file], or in the `at` Snippet when
     * there is one; [lines] are where it does.
     */
    public data class NameNotExactlyOnce(val name: String, val symbol: String, val file: String, val lines: List<SourceLine>) : GetDefinitionResult

    /**
     * [name] (on 1-based [line] of [symbol]) resolves to no declaration: a keyword, a literal or an unresolved
     * reference. Not an error. [diagnostic] is the compiler's finding on the name, if any.
     */
    public data class Unresolved(val name: String, val symbol: String, val line: Int, val diagnostic: String?) : GetDefinitionResult

    /** The index was still incomplete after waiting. */
    public data class NotReady(val readiness: Readiness.NotReady) : GetDefinitionResult
}
