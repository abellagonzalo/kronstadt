package kronstadt.evaluation

/** What counts as Kotlin in a tool input: `.kt` files only (`.kts` build scripts are not covered). */
internal object Kotlin {
    /** A glob aimed at Kotlin: one ending in `.kt`, or a brace set holding `kt` such as `.{kt,kts}`. */
    private val ktGlob = Regex("""\.kt(?![\w.])|\.\{[^}]*\bkt\b[^}]*}""")

    /** A `.kt` path in a shell command or text, delimited by whitespace, quotes, shell punctuation or a `file://` prefix. */
    private val ktToken = Regex("""[^\s'"`<>|;&()=:]*\.kt(?![\w.])""")

    fun isKtPath(path: String?): Boolean = path != null && path.trim().endsWith(".kt")

    fun isKtGlob(glob: String?): Boolean = glob != null && ktGlob.containsMatchIn(glob)

    /** The `.kt` paths named in [text], e.g. a shell command; globs such as `*.kt` are not paths. */
    fun ktPaths(text: String): List<String> =
        ktToken.findAll(text).map { it.value }.filter { it.length > 3 && '*' !in it && '{' !in it }.toList()
}
