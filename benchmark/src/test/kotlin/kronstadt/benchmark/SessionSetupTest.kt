package kronstadt.benchmark

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

class SessionSetupTest {
    @Test
    fun `options default to the low level, Sonnet, 15 minutes and 50 turns`() {
        assertEquals(Options(Level.LOW, "claude-sonnet-5-5", 15.minutes, 50, verifyTasks = false), parseOptions(emptyList()))
        assertEquals(
            Options(Level.HIGH, "claude-opus-5-5", 30.minutes, 80, verifyTasks = true),
            parseOptions(listOf("--level", "high", "--model", "claude-opus-5-5", "--timeout-minutes", "30", "--max-turns", "80", "--verify-tasks")),
        )
    }

    @Test
    fun `bad options are usage errors`() {
        fun problem(vararg args: String) = runCatching { parseOptions(args.toList()) }.exceptionOrNull()?.message
        assertEquals("unknown level 'max'; expected low, medium or high", problem("--level", "max"))
        assertEquals("--max-turns takes a positive whole number, got '0'", problem("--max-turns", "0"))
        assertEquals("--model needs a value", problem("--model"))
        assertEquals("unknown argument: --fast", problem("--fast"))
    }

    @Test
    fun `claude runs headless with streamed JSON, the pinned model, the turn cap, bypassed permissions and only the given MCP servers`() {
        val base = listOf(
            "claude", "-p", "Do it", "--output-format", "stream-json", "--verbose", "--model", "claude-sonnet-5-5",
            "--max-turns", "50", "--dangerously-skip-permissions", "--strict-mcp-config",
        )
        assertEquals(base, ClaudeSession.arguments("Do it", "claude-sonnet-5-5", 50, mcpConfig = null))
        assertEquals(
            base + listOf("--mcp-config", "/out/mcp.json"),
            ClaudeSession.arguments("Do it", "claude-sonnet-5-5", 50, mcpConfig = Path.of("/out/mcp.json")),
        )
    }

    @Test
    fun `claude gets a clean config dir and none of the calling session's Claude Code variables, but keeps its credentials`() {
        val parent = mapOf(
            "PATH" to "/bin",
            "CLAUDECODE" to "1",
            "CLAUDE_CODE_ENTRYPOINT" to "cli",
            "CLAUDE_CONFIG_DIR" to "/home/me/.claude",
            "CLAUDE_CODE_OAUTH_TOKEN" to "token",
            "ANTHROPIC_API_KEY" to "key",
        )

        assertEquals(
            mapOf(
                "PATH" to "/bin",
                "CLAUDE_CODE_OAUTH_TOKEN" to "token",
                "ANTHROPIC_API_KEY" to "key",
                "CLAUDE_CONFIG_DIR" to "/out/config",
                "DISABLE_AUTOUPDATER" to "1",
            ),
            ClaudeSession.environment(parent, Path.of("/out/config")),
        )
        assertTrue(ClaudeSession.hasCredentials(mapOf("ANTHROPIC_API_KEY" to "key")))
        assertTrue(ClaudeSession.hasCredentials(mapOf("CLAUDE_CODE_OAUTH_TOKEN" to "token")))
        assertFalse(ClaudeSession.hasCredentials(mapOf("ANTHROPIC_API_KEY" to "")))
    }

    @Test
    fun `the Kronstadt arm's MCP config starts the server built from this checkout on the run worktree`() {
        assertEquals(
            """{"mcpServers":{"kronstadt":{"type":"stdio","command":"/repo/kronstadt","args":["--project-root","/runs/nav-1-with-1"]}}}""",
            ClaudeSession.mcpConfig(Path.of("/repo/kronstadt"), Path.of("/runs/nav-1-with-1")),
        )
    }

    @Test
    fun `the Kotlin instructions are the README's recommended CLAUDE_md section, without the part on opening issues`() {
        val readme = Files.readString(Path.of("../README.md"))

        val instructions = KotlinInstructions.fromReadme(readme)

        assertTrue(instructions.startsWith("## Kotlin code (Kronstadt)\n"))
        assertTrue("kt_rename_symbol" in instructions)
        assertTrue(instructions.trimEnd().endsWith("one change only."), instructions)
        assertFalse("gh issue" in instructions)
        assertFalse("```" in instructions)
    }
}
