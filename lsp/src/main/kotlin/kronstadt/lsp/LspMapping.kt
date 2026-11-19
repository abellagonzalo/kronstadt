package kronstadt.lsp

import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.FileChange
import kronstadt.core.domain.Position
import kronstadt.core.domain.Rename
import kronstadt.core.domain.RenamePreparation
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.TextRange
import org.eclipse.lsp4j.DiagnosticSeverity
import org.eclipse.lsp4j.DocumentDiagnosticReport
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Path
import org.eclipse.lsp4j.Diagnostic as LspDiagnostic
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range as LspRange
import org.eclipse.lsp4j.RenameFile
import org.eclipse.lsp4j.SymbolKind as LspSymbolKind
import org.eclipse.lsp4j.TextEdit as LspTextEdit
import org.eclipse.lsp4j.WorkspaceEdit

/** Pure conversions between lsp4j types and Kronstadt's domain. Keeps protocol types out of `core`. */
internal object LspMapping {
    fun position(p: LspPosition): Position = Position(p.line, p.character)

    fun range(r: LspRange): TextRange = TextRange(position(r.start), position(r.end))

    fun kind(kind: LspSymbolKind?): SymbolKind = when (kind) {
        LspSymbolKind.Package, LspSymbolKind.Namespace, LspSymbolKind.Module -> SymbolKind.PACKAGE
        LspSymbolKind.Class, LspSymbolKind.Struct -> SymbolKind.CLASS
        LspSymbolKind.Interface -> SymbolKind.INTERFACE
        LspSymbolKind.Enum -> SymbolKind.ENUM
        LspSymbolKind.EnumMember -> SymbolKind.ENUM_ENTRY
        LspSymbolKind.Object -> SymbolKind.OBJECT
        LspSymbolKind.Constructor -> SymbolKind.CONSTRUCTOR
        LspSymbolKind.Function, LspSymbolKind.Method, LspSymbolKind.Operator -> SymbolKind.FUNCTION
        LspSymbolKind.Property, LspSymbolKind.Field, LspSymbolKind.Constant -> SymbolKind.PROPERTY
        LspSymbolKind.Variable -> SymbolKind.VARIABLE
        LspSymbolKind.TypeParameter -> SymbolKind.TYPE_PARAMETER
        else -> SymbolKind.OTHER
    }

    fun documentSymbol(s: DocumentSymbol): CodeSymbol = CodeSymbol(
        name = s.name,
        kind = kind(s.kind),
        range = range(s.range),
        selectionRange = range(s.selectionRange),
        children = s.children.orEmpty().map(::documentSymbol),
        detail = s.detail,
    )

    /**
     * Servers that ignore our hierarchical capability return flat [SymbolInformation].
     * Those carry no separate name range, so the declaration range stands in for both.
     */
    fun documentSymbols(result: List<Either<SymbolInformation, DocumentSymbol>>?): List<CodeSymbol> =
        result.orEmpty().map { either ->
            if (either.isRight) {
                documentSymbol(either.right)
            } else {
                val info = either.left
                CodeSymbol(info.name, kind(info.kind), range(info.location.range), range(info.location.range))
            }
        }

    /** Workspace symbols, keeping only those in local files (dependency symbols come back as jar:/custom URIs). */
    fun workspaceSymbols(
        result: Either<List<SymbolInformation>, List<WorkspaceSymbol>>?,
    ): List<SymbolLocation> {
        if (result == null) return emptyList()
        return if (result.isLeft) {
            result.left.mapNotNull { info ->
                val file = localPath(info.location.uri) ?: return@mapNotNull null
                SymbolLocation(info.name, kind(info.kind), file, range(info.location.range), info.containerName)
            }
        } else {
            result.right.mapNotNull { ws ->
                // A WorkspaceSymbolLocation (URI only, no range) means the server wants a resolve round-trip;
                // not supported yet, so such results are dropped.
                val location = ws.location.takeIf { it.isLeft }?.left ?: return@mapNotNull null
                val file = localPath(location.uri) ?: return@mapNotNull null
                SymbolLocation(ws.name, kind(ws.kind), file, range(location.range), ws.containerName)
            }
        }
    }

    /**
     * The most results kotlin-lsp 271.2085.0 returns for `workspace/symbol`, however many symbols match, dependencies'
     * included; it sends no sign that it left any out (pinned by `WorkspaceSymbolCapContractTest`).
     */
    const val WORKSPACE_SYMBOL_CAP = 200

    /**
     * Whether [result], kotlin-lsp's answer to [query], may have left matches out. Below [WORKSPACE_SYMBOL_CAP] results
     * nothing was left out. kotlin-lsp lists symbols named like the query first, so a full page that ends in another
     * name left out only names that merely contain the query. One that ends in the query's name (ignoring case, to be
     * safe) may have left out symbols named exactly like it. Counts every result, dependency symbols included.
     */
    fun workspaceSymbolCompleteness(
        result: Either<List<SymbolInformation>, List<WorkspaceSymbol>>?,
        query: String,
    ): SymbolSearch.Completeness {
        val names = when {
            result == null -> emptyList()
            result.isLeft -> result.left.map { it.name }
            else -> result.right.map { it.name }
        }
        return when {
            names.size < WORKSPACE_SYMBOL_CAP -> SymbolSearch.Completeness.COMPLETE
            names.last().equals(query, ignoreCase = true) -> SymbolSearch.Completeness.INCOMPLETE
            else -> SymbolSearch.Completeness.EXACT_NAMES_COMPLETE
        }
    }

    /**
     * A full report's diagnostics. An "unchanged" report can only answer a request that sent a previous result id,
     * which we never do, so it is treated as a protocol error rather than as "no diagnostics".
     */
    fun diagnostics(report: DocumentDiagnosticReport?): List<Diagnostic> {
        requireNotNull(report) { "language server returned no diagnostic report" }
        check(report.isRelatedFullDocumentDiagnosticReport) { "language server returned an 'unchanged' diagnostic report unasked" }
        return report.relatedFullDocumentDiagnosticReport.items.orEmpty().map(::diagnostic)
    }

    fun diagnostic(d: LspDiagnostic): Diagnostic = Diagnostic(
        range = range(d.range),
        // The spec says a missing severity is up to the client; an unknown finding is safest treated as an error.
        severity = when (d.severity) {
            DiagnosticSeverity.Warning -> Severity.WARNING
            DiagnosticSeverity.Information -> Severity.INFORMATION
            DiagnosticSeverity.Hint -> Severity.HINT
            DiagnosticSeverity.Error, null -> Severity.ERROR
        },
        message = d.message?.map({ it }, { it.value }).orEmpty(),
        code = d.code?.map({ it }, { it.toString() }),
    )

    fun localPath(uri: String): Path? = runCatching {
        URI(uri).takeIf { it.scheme == "file" }?.let(Path::of)
    }.getOrNull()

    /** Locations in local files; ones in dependencies (jar: and other non-file URIs) are dropped. */
    fun locations(result: List<Location?>?): List<SourceLocation> =
        result.orEmpty().filterNotNull().mapNotNull { location ->
            localPath(location.uri)?.let { SourceLocation(it, range(location.range)) }
        }

    /**
     * Where a `textDocument/definition` result points, from its URI: a local file, an entry in a jar
     * (`jar:///<jar>!/<entry>`, a sources jar for Kotlin libraries) or a JDK class (`jrt:///<jdk>!/<module>/<entry>`).
     * Null for any other URI.
     */
    fun definitionTarget(uri: String): DefinitionTarget? {
        localPath(uri)?.let { return DefinitionTarget.Local(it) }
        val (archive, entry) = archiveParts(uri, "jar:") ?: archiveParts(uri, "jrt:")?.let { (_, path) ->
            val module = path.substringBefore('/')
            return DefinitionTarget.Jdk(module, path.substringAfter('/'))
        } ?: return null
        return DefinitionTarget.Jar(archive, entry)
    }

    /** `<scheme>///<archive path>!/<entry>` split into the archive's path and the entry, URL-decoded. */
    private fun archiveParts(uri: String, scheme: String): Pair<Path, String>? {
        if (!uri.startsWith(scheme)) return null
        val separator = uri.indexOf("!/").takeIf { it > 0 } ?: return null
        val archive = runCatching { Path.of(URI("file:" + uri.substring(scheme.length, separator))) }.getOrNull() ?: return null
        // A literal '+' stays a '+': URLDecoder would read it as a space.
        val entry = URLDecoder.decode(uri.substring(separator + 2).replace("+", "%2B"), Charsets.UTF_8)
        return archive to entry
    }

    /** The URI and name range of each `textDocument/definition` result, whether Locations or LocationLinks. */
    fun definitionLocations(result: Either<List<Location?>, List<LocationLink?>>?): List<Pair<String, TextRange>> = when {
        result == null -> emptyList()
        result.isLeft -> result.left.orEmpty().filterNotNull().map { it.uri to range(it.range) }
        else -> result.right.orEmpty().filterNotNull().map { it.targetUri to range(it.targetSelectionRange ?: it.targetRange) }
    }

    /**
     * kotlin-lsp's answer to `prepareRename` at [position]: a range, with or without a placeholder, or "default behaviour"
     * (the word at the position); null means it can't be renamed.
     */
    fun prepareRename(result: Either3<LspRange, PrepareRenameResult, PrepareRenameDefaultBehavior>?, position: Position): RenamePreparation =
        when {
            result == null -> RenamePreparation.Refused("This element cannot be renamed")
            result.isFirst -> RenamePreparation.Renameable(range(result.first), placeholder = "")
            result.isSecond -> RenamePreparation.Renameable(range(result.second.range), result.second.placeholder.orEmpty())
            else -> RenamePreparation.Renameable(TextRange(position, position), placeholder = "")
        }

    /**
     * kotlin-lsp's answer to `rename`, in its order: `documentChanges` if given (text edits and file renames), else
     * `changes`. Refused if it would create or delete a file, edit with a snippet, or touch a URI that isn't a file.
     */
    fun rename(edit: WorkspaceEdit?): Rename {
        if (edit == null) return Rename.Changes(emptyList())
        fun file(uri: String) = localPath(uri)
        val documentChanges = edit.documentChanges
            ?: return Rename.Changes(
                edit.changes.orEmpty().map { (uri, edits) ->
                    FileChange.Edited(file(uri) ?: return Rename.Refused("kotlin-lsp would change $uri"), edits.map(::textEdit))
                },
            )
        return Rename.Changes(
            documentChanges.map { change ->
                if (change.isLeft) {
                    val document = change.left
                    val path = file(document.textDocument.uri) ?: return Rename.Refused("kotlin-lsp would change ${document.textDocument.uri}")
                    FileChange.Edited(
                        path,
                        document.edits.map { if (it.isLeft) textEdit(it.left) else return Rename.Refused("kotlin-lsp answered with a snippet edit") },
                    )
                } else {
                    when (val operation = change.right) {
                        is RenameFile -> FileChange.Moved(
                            file(operation.oldUri) ?: return Rename.Refused("kotlin-lsp would move ${operation.oldUri}"),
                            file(operation.newUri) ?: return Rename.Refused("kotlin-lsp would move a file to ${operation.newUri}"),
                        )
                        else -> return Rename.Refused("kotlin-lsp would ${operation.kind} a file")
                    }
                }
            },
        )
    }

    /**
     * [document] with the [edits] a command sent for [uri] applied, in order; null if one also changes another file, or
     * isn't plain text edits; no edit means nothing to drop. kotlin-lsp 271.2085.0 sends organize-imports as one `changes` entry for the file.
     */
    fun organized(document: TextDocument, uri: String, edits: List<WorkspaceEdit>): TextDocument? {
        val file = localPath(uri)
        return edits.fold(document) { text, edit ->
            val changes = edit.documentChanges?.map { change ->
                val changed = change.left?.takeIf { localPath(it.textDocument.uri) == file } ?: return null
                changed.edits.map { if (it.isLeft) it.left else return null }
            } ?: edit.changes.orEmpty().map { (target, changed) -> if (localPath(target) == file) changed else return null }
            // Ranges refer to the text before the edit, so the last one goes first.
            changes.flatten().map(::textEdit).sortedByDescending { it.range.start }.fold(text) { acc, it -> acc.replace(it.range, it.newText) }
        }
    }

    /** An LSP text edit as Kronstadt's. */
    fun textEdit(edit: LspTextEdit): TextEdit = TextEdit(range(edit.range), edit.newText)

    fun uri(file: Path): String = file.toUri().toString()
}

/** A definition result's URI, taken apart. */
internal sealed interface DefinitionTarget {
    data class Local(val file: Path) : DefinitionTarget

    /** [entry] inside the jar at [jar]. */
    data class Jar(val jar: Path, val entry: String) : DefinitionTarget

    /** A class file [entry] in the JDK's [module]. */
    data class Jdk(val module: String, val entry: String) : DefinitionTarget
}
