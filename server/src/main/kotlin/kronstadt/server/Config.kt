package kronstadt.server

import kronstadt.core.domain.Readiness
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Command-line configuration. Each Kronstadt instance serves exactly one project root (one worktree). */
internal data class Config(
    val projectRoot: Path,
    val languageServer: List<String>,
    val readinessTimeout: Duration,
) {
    companion object {
        const val USAGE = """Usage: kronstadt [--project-root <dir>] [--language-server "<command>"] [--readiness-timeout <seconds>]

  --project-root       Project (or Git worktree) to serve. Default: current directory.
  --language-server    Which kotlin-lsp to run: the command that starts it over stdio.
                       Default: ${'$'}KRONSTADT_LANGUAGE_SERVER, else "kotlin-lsp --stdio".
  --readiness-timeout  Seconds a tool waits for kotlin-lsp to finish importing and indexing before failing
                       with a retry error. Default: ${'$'}KRONSTADT_READINESS_TIMEOUT, else 90."""

        fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): Config {
            var root: String? = null
            var languageServer: String? = null
            var readinessTimeout: String? = null
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--project-root" -> root = args.getOrNull(++i) ?: usageError("--project-root needs a value")
                    "--language-server" -> languageServer = args.getOrNull(++i) ?: usageError("--language-server needs a value")
                    "--readiness-timeout" -> readinessTimeout = args.getOrNull(++i) ?: usageError("--readiness-timeout needs a value")
                    "-h", "--help" -> usageError(null)
                    else -> usageError("unknown argument: ${args[i]}")
                }
                i++
            }
            val command = (languageServer ?: env["KRONSTADT_LANGUAGE_SERVER"] ?: "kotlin-lsp --stdio")
                .trim().split(Regex("\\s+"))
            val timeout = (readinessTimeout ?: env["KRONSTADT_READINESS_TIMEOUT"])?.let(::seconds) ?: Readiness.DEFAULT_TIMEOUT
            return Config(Path.of(root ?: "").toAbsolutePath().normalize(), command, timeout)
        }

        private fun seconds(value: String): Duration =
            value.toLongOrNull()?.takeIf { it > 0 }?.seconds
                ?: usageError("readiness timeout must be a positive whole number of seconds, got '$value'")

        private fun usageError(message: String?): Nothing =
            throw IllegalArgumentException(listOfNotNull(message, USAGE).joinToString("\n\n"))
    }
}
