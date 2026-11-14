package kronstadt.lsp

import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Position
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolLocation
import kronstadt.core.domain.TextRange
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.net.URI
import java.nio.file.Path
import org.eclipse.lsp4j.Position as LspPosition
import org.eclipse.lsp4j.Range as LspRange
import org.eclipse.lsp4j.SymbolKind as LspSymbolKind

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

    fun localPath(uri: String): Path? = runCatching {
        URI(uri).takeIf { it.scheme == "file" }?.let(Path::of)
    }.getOrNull()

    fun uri(file: Path): String = file.toUri().toString()
}
