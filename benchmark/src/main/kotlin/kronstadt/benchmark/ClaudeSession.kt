package kronstadt.benchmark

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.nio.file.Path

/**
 * How the Benchmark launches `claude`: headless, with the same prompt, model and permissions in both arms. Each
 * session gets a clean `CLAUDE_CONFIG_DIR`, so the user's own CLAUDE.md, skills and MCP servers don't leak in, and
 * only the MCP servers given (`--strict-mcp-config`), so the arm without Kronstadt has none. Permissions are
 * bypassed: the session runs in a throwaway run worktree.
 */
internal object ClaudeSession {
    fun arguments(prompt: String, model: String, maxTurns: Int, mcpConfig: Path?): List<String> = buildList {
        addAll(listOf("claude", "-p", prompt, "--output-format", "stream-json", "--verbose"))
        addAll(listOf("--model", model, "--max-turns", maxTurns.toString()))
        addAll(listOf("--dangerously-skip-permissions", "--strict-mcp-config"))
        if (mcpConfig != null) addAll(listOf("--mcp-config", mcpConfig.toString()))
    }

    /**
     * [parent] without the Claude Code variables of the session the Benchmark may itself run in, except the
     * credentials, with [configDir] as the config dir and no auto-update mid-Benchmark.
     */
    fun environment(parent: Map<String, String>, configDir: Path): Map<String, String> =
        parent.filterKeys { !it.startsWith("CLAUDE") || it == OAUTH_TOKEN } +
            mapOf("CLAUDE_CONFIG_DIR" to configDir.toString(), "DISABLE_AUTOUPDATER" to "1")

    /**
     * Whether `claude` can authenticate with a clean config dir, which holds no login: only through an API key or a
     * long-lived OAuth token (`claude setup-token`) in the environment.
     */
    fun hasCredentials(environment: Map<String, String>): Boolean =
        listOf(API_KEY, OAUTH_TOKEN).any { !environment[it].isNullOrBlank() }

    /** The `--mcp-config` of the arm with Kronstadt: the server at [kronstadt], serving [worktree]. */
    fun mcpConfig(kronstadt: Path, worktree: Path): String = buildJsonObject {
        putJsonObject("mcpServers") {
            putJsonObject("kronstadt") {
                put("type", "stdio")
                put("command", kronstadt.toString())
                putJsonArray("args") {
                    add("--project-root")
                    add(worktree.toString())
                }
            }
        }
    }.toString()

    const val API_KEY: String = "ANTHROPIC_API_KEY"
    const val OAUTH_TOKEN: String = "CLAUDE_CODE_OAUTH_TOKEN"
}

/** The Kotlin instructions for CLAUDE.md that the README recommends, which the arm with Kronstadt gets. */
internal object KotlinInstructions {
    private const val START = "## Kotlin code (Kronstadt)"
    private const val REPORTING = "### Reporting Kronstadt bugs"

    /**
     * The README's recommended section, read from it so the two never drift apart, without its subsection on
     * reporting Kronstadt bugs, which would have agents open issues in a public repo mid-Benchmark.
     */
    fun fromReadme(readme: String): String {
        val start = readme.indexOf(START)
        require(start >= 0) { "README.md has no '$START' section" }
        val end = readme.indexOf(REPORTING, start)
        require(end >= 0) { "README.md's '$START' section has no '$REPORTING' subsection to stop at" }
        return readme.substring(start, end).trim() + "\n"
    }
}
