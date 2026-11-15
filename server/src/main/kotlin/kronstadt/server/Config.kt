package kronstadt.server

import kronstadt.core.domain.Readiness
import kronstadt.core.domain.Validation
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Command-line configuration. Each Kronstadt instance serves exactly one project root (one worktree). */
internal data class Config(
    val projectRoot: Path,
    val languageServer: List<String>,
    val readinessTimeout: Duration,
    val validationTimeout: Duration = Validation.DEFAULT_TIMEOUT,
) {
    companion object {
        const val USAGE = """Usage: kronstadt [--project-root <dir>] [--language-server "<command>"] [--readiness-timeout <seconds>]
                 [--validation-timeout <seconds>]

  --project-root       Project (or Git worktree) to serve. Default: current directory.
  --language-server    Which kotlin-lsp to run: the command that starts it over stdio.
                       Default: ${'$'}KRONSTADT_LANGUAGE_SERVER, else "kotlin-lsp --stdio".
  --readiness-timeout  Seconds a tool waits for kotlin-lsp to finish importing and indexing before failing
                       with a retry error. Default: ${'$'}KRONSTADT_READINESS_TIMEOUT, else 90.
  --validation-timeout Seconds kt_edit waits for diagnostics before rejecting the edit with nothing written.
                       Default: ${'$'}KRONSTADT_VALIDATION_TIMEOUT, else 15."""

        fun parse(args: Array<String>, env: Map<String, String> = System.getenv()): Config {
            var root: String? = null
            var languageServer: String? = null
            var readinessTimeout: String? = null
            var validationTimeout: String? = null
            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--project-root" -> root = args.getOrNull(++i) ?: usageError("--project-root needs a value")
                    "--language-server" -> languageServer = args.getOrNull(++i) ?: usageError("--language-server needs a value")
                    "--readiness-timeout" -> readinessTimeout = args.getOrNull(++i) ?: usageError("--readiness-timeout needs a value")
                    "--validation-timeout" -> validationTimeout = args.getOrNull(++i) ?: usageError("--validation-timeout needs a value")
                    "-h", "--help" -> usageError(null)
                    else -> usageError("unknown argument: ${args[i]}")
                }
                i++
            }
            val command = (languageServer ?: env["KRONSTADT_LANGUAGE_SERVER"] ?: "kotlin-lsp --stdio")
                .trim().split(Regex("\\s+"))
            val timeout = (readinessTimeout ?: env["KRONSTADT_READINESS_TIMEOUT"])?.let { seconds("readiness", it) }
                ?: Readiness.DEFAULT_TIMEOUT
            val validation = (validationTimeout ?: env["KRONSTADT_VALIDATION_TIMEOUT"])?.let { seconds("validation", it) }
                ?: Validation.DEFAULT_TIMEOUT
            return Config(Path.of(root ?: "").toAbsolutePath().normalize(), command, timeout, validation)
        }

        private fun seconds(which: String, value: String): Duration =
            value.toLongOrNull()?.takeIf { it > 0 }?.seconds
                ?: usageError("$which timeout must be a positive whole number of seconds, got '$value'")

        private fun usageError(message: String?): Nothing =
            throw IllegalArgumentException(listOfNotNull(message, USAGE).joinToString("\n\n"))
    }
}
