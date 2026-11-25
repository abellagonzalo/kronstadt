package kronstadt.core.domain

import java.nio.file.Path

/** Kotlin-relevant symbol kinds. Adapters map their protocol's kinds onto these. */
public enum class SymbolKind(public val keyword: String) {
    PACKAGE("package"),
    CLASS("class"),
    INTERFACE("interface"),
    ENUM("enum class"),
    ENUM_ENTRY("entry"),
    OBJECT("object"),
    CONSTRUCTOR("constructor"),
    FUNCTION("fun"),
    PROPERTY("val"),
    VARIABLE("var"),
    TYPE_PARAMETER("type"),
    OTHER("symbol"),
    ;

    /** Whether this kind can contain declarations worth showing in a structure skeleton. */
    public val isContainer: Boolean get() = this in setOf(PACKAGE, CLASS, INTERFACE, ENUM, OBJECT)
}

/** A symbol inside one file, with its children (e.g. a class and its members). */
public data class CodeSymbol(
    val name: String,
    val kind: SymbolKind,
    /** Full extent of the declaration, including its body. */
    val range: TextRange,
    /** The range of the symbol's name (identifier) only. */
    val selectionRange: TextRange,
    val children: List<CodeSymbol> = emptyList(),
    /** An extension's receiver type, e.g. `String` or `List<T>`; null for Symbols that aren't extensions. */
    val receiver: String? = null,
    /**
     * A function's or constructor's Signature: the text between its parameter parentheses, e.g. `String, Int`, and
     * empty for `()`. Null for Symbols that take no parameter list.
     */
    val parameterTypes: String? = null,
    /**
     * The text `kt_read_structure` shows next to the Symbol's path, without an extension receiver: `(String): Int` for
     * a function, `: Char` for a property. Null when the language server shows nothing.
     */
    val type: String? = null,
)

/**
 * Where to point the code intelligence at this Symbol's name in [document]: inside the identifier. A backticked name's
 * [CodeSymbol.selectionRange] starts at its opening backtick, where kotlin-lsp finds no name to rename, so it's the
 * character after it.
 */
public fun CodeSymbol.namePosition(document: TextDocument): Position {
    val start = selectionRange.start
    val backticked = start < selectionRange.end && document.text.getOrNull(document.offsetOf(start)) == '`'
    return if (backticked) start.copy(character = start.character + 1) else start
}

/** A symbol found by a workspace-wide search. */
public data class SymbolLocation(
    val name: String,
    val kind: SymbolKind,
    val file: Path,
    val range: TextRange,
    /** Enclosing declaration, e.g. the class a method belongs to, if the language server reports it. */
    val containerName: String? = null,
)

/** What a workspace-wide symbol search found, and whether it may have left matches out. */
public data class SymbolSearch(val symbols: List<SymbolLocation>, val completeness: Completeness = Completeness.COMPLETE) {
    /** Whether the search may have left matches out, e.g. because the code intelligence stops at a maximum number of results. */
    public val mayBeIncomplete: Boolean get() = completeness != Completeness.COMPLETE

    public enum class Completeness {
        /** Every match came back. */
        COMPLETE,

        /** Every symbol named exactly like the query came back; some whose names merely contain it may not have. */
        EXACT_NAMES_COMPLETE,

        /** Even symbols named exactly like the query may be missing. */
        INCOMPLETE,
    }
}

/** A range in a project file, e.g. where a Usage of a Symbol is. */
public data class SourceLocation(val file: Path, val range: TextRange)
