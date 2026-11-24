package kronstadt.core.domain

import java.nio.file.Path

// The test builder for AnalysedFiles and the Symbols in them, as kotlin-lsp reports them.

/** A range from [startLine]:[startChar] to [endLine]:[endChar], all 0-based. */
internal fun range(startLine: Int, startChar: Int, endLine: Int, endChar: Int): TextRange =
    TextRange(Position(startLine, startChar), Position(endLine, endChar))

/** A Symbol over [range] whose name sits at [nameLine]:[nameChar]. */
internal fun symbol(
    name: String,
    kind: SymbolKind,
    range: TextRange,
    nameLine: Int,
    nameChar: Int,
    parameterTypes: String? = null,
    type: String? = null,
    receiver: String? = null,
    children: List<CodeSymbol> = emptyList(),
): CodeSymbol = CodeSymbol(
    name, kind, range, range(nameLine, nameChar, nameLine, nameChar + name.length), children,
    parameterTypes = parameterTypes, type = type, receiver = receiver,
)

/** [document] with [symbols] as its `documentSymbol` tree, in a file shown as [shown] under `/project`. */
internal fun analysedFile(document: TextDocument, symbols: List<CodeSymbol>, shown: String = "src/A.kt"): AnalysedFile =
    AnalysedFile(Path.of("/project").resolve(shown), shown, document, symbols)
