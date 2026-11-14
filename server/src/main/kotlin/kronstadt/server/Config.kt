package kronstadt.server

import java.nio.file.Path

/** Command-line configuration. Each Kronstadt instance serves exactly one project root (one worktree). */
internal data class Config(
    val projectRoot: Path,
    val languageServer: List<String>,
) {
    companion object {
        const val USAGE = """Usage: kronstadt [--project-root <dir>] [--language-server "<command>"]

  --project-root     Project (or Git worktree) to serve. Default: current directory.
  --language-server  Command that starts a Kotlin language server over stdio.
                     Default: ${'$'}KRONSTADT_LANGUAGE_SERVER, else "kotlin-language-server"."""

        fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): Config {
            var root: String? = null
            var languageServer: String? = null
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--project-root" -> root = args.getOrNull(++i) ?: usageError("--project-root needs a value")
                    "--language-server" -> languageServer = args.getOrNull(++i) ?: usageError("--language-server needs a value")
                    "-h", "--help" -> usageError(null)
                    else -> usageError("unknown argument: ${args[i]}")
                }
                i++
            }
            val command = (languageServer ?: env["KRONSTADT_LANGUAGE_SERVER"] ?: "kotlin-language-server")
                .trim().split(Regex("\\s+"))
            return Config(Path.of(root ?: "").toAbsolutePath().normalize(), command)
        }

        private fun usageError(message: String?): Nothing =
            throw IllegalArgumentException(listOfNotNull(message, USAGE).joinToString("\n\n"))
    }
}
