package kronstadt.core.application

import kronstadt.core.domain.AnalysedFile
import kronstadt.core.domain.Definition
import kronstadt.core.domain.DeclarationHead
import kronstadt.core.domain.Position
import kronstadt.core.domain.Readiness
import kronstadt.core.domain.SymbolListing
import kronstadt.core.domain.SymbolSpan
import kronstadt.core.domain.SymbolTree
import kronstadt.core.domain.TextRange
import kronstadt.core.domain.packageName
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.CodeIntelligence
import kronstadt.core.port.FileStore
import kotlin.time.Duration

/**
 * Use case behind getting a definition: where a name used inside a Symbol is declared, and that declaration's contract
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
        return GetDefinitionResult.Found(
            definitions.map { definition ->
                when (definition) {
                    is Definition.InSource -> sourceTarget(session, definition, name)
                    is Definition.InLibrary -> libraryTarget(definition, name)
                }
            },
        )
    }

    /** [name] as a whole identifier, not part of a longer one; backticks optional. */
    private fun identifier(name: String): Regex {
        val bare = name.removeSurrounding("`")
        return Regex("""(?<![\p{L}\p{N}_`])`?""" + Regex.escape(bare) + """`?(?![\p{L}\p{N}_`])""")
    }

    /**
     * A declaration in a source file: a project Symbol, a local, or something in a file Kronstadt can't address (not
     * Kotlin, or outside the project), with the line it is on. [name] stands in for a name that can't be read.
     */
    private suspend fun sourceTarget(session: AnalysisSession, definition: Definition.InSource, name: String): FoundDefinition {
        val location = definition.location
        val file = location.file
        val line = location.range.start.line
        if (files.resolve(file.toString()) != file) return FoundDefinition.OutsideProject(name, file.toString(), line + 1)
        val shown = files.display(file)
        if (!AnalysedFile.isKotlin(file)) {
            val document = files.read(file)?.takeIf { line < it.lineCount }
            return FoundDefinition.NotKotlin(document?.slice(location.range) ?: name, shown, line + 1, document?.line(line)?.trim())
        }
        val analysed = session.analyse(files, file) ?: return FoundDefinition.FileNotFound(name, shown, line + 1)
        val node = analysed.nodes.lastOrNull { it.symbol.selectionRange.start == location.range.start }
            ?: return local(analysed, location.range)
        return FoundDefinition.InSource(
            analysed.listing(node),
            shown,
            DeclarationHead.of(analysed.document, node.symbol, analysed.listedLines(node).first),
        )
    }

    /**
     * A library or JDK declaration, which no tool can edit. With sources: its fully qualified name, then its KDoc and
     * declaration without the body. Without, or when no Symbol is at the definition (as for Java source kotlin-lsp gives no
     * Symbols for): the class it is in, from the class or Java file's path.
     */
    private fun libraryTarget(definition: Definition.InLibrary, name: String): FoundDefinition {
        val source = definition.source
        val node = source?.let { SymbolTree(it.symbols).nodes.lastOrNull { node -> node.symbol.selectionRange.start == definition.range.start } }
        if (source == null || node == null) {
            val className = definition.entry.removeSuffix(".class").removeSuffix(".java").replace('/', '.').replace('$', '.')
            val member = name.takeUnless { className.substringAfterLast('.') == it }
            return FoundDefinition.LibraryWithoutSources(member, className, definition.origin)
        }
        val fqName = (listOf(source.document.packageName) + (node.ancestors + node.symbol).map { it.name })
            .filter { it.isNotEmpty() }.joinToString(".")
        val (first, _) = SymbolSpan.lines(source.document, node.symbol)
        return FoundDefinition.InLibrary(fqName, definition.origin, definition.entry, DeclarationHead.of(source.document, node.symbol, first))
    }

    /** A declaration no Symbol Path addresses (a local variable, parameter or local function): where it is. */
    private fun local(analysed: AnalysedFile, range: TextRange): FoundDefinition {
        val enclosing = analysed.nodes.lastOrNull { range.start in it.symbol.range }?.let { analysed.path(it).toString() }
        val line = range.start.line
        return FoundDefinition.Local(analysed.document.slice(range), enclosing, analysed.shown, line + 1, analysed.document.line(line).trim())
    }
}

public sealed interface GetDefinitionResult {
    /** Each definition, in the code intelligence's order. */
    public data class Found(val definitions: List<FoundDefinition>) : GetDefinitionResult
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

/** One declaration a name resolved to. Lines are 1-based; a file is as shown, unless outside the project. */
public sealed interface FoundDefinition {
    /** A project Symbol: its [listing] (canonical Symbol Path and line span), and its KDoc and declaration without the body. */
    public data class InSource(val listing: SymbolListing, val file: String, val head: String) : FoundDefinition

    /** A local variable, parameter or local function, inside the Symbol at [enclosing] (`null`: outside any), and its declaration's [source] line. */
    public data class Local(val name: String, val enclosing: String?, val file: String, val line: Int, val source: String) : FoundDefinition

    /** A library or JDK declaration with sources: its fully qualified name, where it is, and its KDoc and declaration. */
    public data class InLibrary(val qualifiedName: String, val origin: String, val entry: String, val head: String) : FoundDefinition

    /** A library or JDK declaration without sources: the class it is in, and the [member] name unless it is that class. */
    public data class LibraryWithoutSources(val member: String?, val className: String, val origin: String) : FoundDefinition

    /** A declaration in a file that isn't Kotlin, and its [source] line if the file could be read. */
    public data class NotKotlin(val name: String, val file: String, val line: Int, val source: String?) : FoundDefinition

    /** A declaration outside the project, at its absolute [file]. */
    public data class OutsideProject(val name: String, val file: String, val line: Int) : FoundDefinition

    /** A declaration in a Kotlin file that could no longer be read. */
    public data class FileNotFound(val name: String, val file: String, val line: Int) : FoundDefinition
}
