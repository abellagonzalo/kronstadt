package kronstadt.core.domain

/**
 * A bare Symbol Path chose a Symbol of [kind], not a function, over [functions] of the same name in the same container
 * (ADR 0002). Each of [functions] is a Symbol Path naming that function alone, in the same file.
 */
public data class PreferredOverFunctions(val kind: SymbolKind, val functions: List<String>) {
    /** The chosen Symbol's kind as messages name it, e.g. `the property`. */
    val picked: String
        get() = "the " + when (kind) {
            SymbolKind.PROPERTY, SymbolKind.VARIABLE -> "property"
            SymbolKind.ENUM_ENTRY -> "enum entry"
            SymbolKind.TYPE_PARAMETER -> "type parameter"
            SymbolKind.OTHER -> "Symbol"
            else -> kind.keyword
        }

    /** The functions it was chosen over, as a lowercase clause: `a function shares its name: edit(Int)`. */
    val sharedName: String
        get() = (if (functions.size == 1) "a function shares its name: " else "functions share its name: ") + functions.joinToString(", ")
}
