package kronstadt.lsp

/** The kotlin-lsp build Kronstadt is tested against. Other builds may behave differently, so we warn, never refuse. */
internal object KotlinLspVersion {
    const val TESTED: String = "271.2085.0"

    private val number = Regex("""\d+(\.\d+)+""")

    /** A warning if [reported] (kotlin-lsp's `serverInfo.version`, e.g. `ILS-271.2085.0`) isn't the tested build. */
    fun mismatchWarning(reported: String?): String? {
        if (reported != null && number.find(reported)?.value == TESTED) return null
        return "kronstadt: warning: kotlin-lsp reports version ${reported ?: "(none)"}, but Kronstadt is tested " +
            "against $TESTED; tools may misbehave."
    }
}
