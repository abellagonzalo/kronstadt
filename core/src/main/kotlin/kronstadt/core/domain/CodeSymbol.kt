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
)

/** A symbol found by a workspace-wide search. */
public data class SymbolLocation(
    val name: String,
    val kind: SymbolKind,
    val file: Path,
    val range: TextRange,
    /** Enclosing declaration, e.g. the class a method belongs to, if the language server reports it. */
    val containerName: String? = null,
)
