package fixture.gamma

/** For kt_rename_symbol (#85): renaming `signature` to `parameterTypes` comes back as several edits per name. */
data class Sym(val name: String, val signature: List<String>)
