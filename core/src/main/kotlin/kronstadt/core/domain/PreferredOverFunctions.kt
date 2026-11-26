package kronstadt.core.domain

/**
 * A bare Symbol Path chose a Symbol of [kind], not a function, over [functions] of the same name in the same container
 * (ADR 0002). Each of [functions] is a Symbol Path naming that function alone, in the same file.
 */
public data class PreferredOverFunctions(val kind: SymbolKind, val functions: List<String>)
