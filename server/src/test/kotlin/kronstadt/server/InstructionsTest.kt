package kronstadt.server

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** The server instructions route agents to every tool Kronstadt registers, and away from built-in fallbacks. */
class InstructionsTest {
    private val root: Path = Files.createTempDirectory("kt-instructions").toRealPath()

    private val registeredTools: Set<String> = ToolHarness(root, UnstubbedCodeIntelligence()).toolNames

    private val instructions = serverInstructions(root)

    @Test
    fun `names every registered tool exactly`() {
        val missing = registeredTools.filterNot { Regex("\\b$it\\b").containsMatchIn(instructions) }
        assertTrue(missing.isEmpty(), "instructions don't name $missing:\n$instructions")
    }

    @Test
    fun `routes reads, searches and edits away from the built-in tools`() {
        listOf("Read", "grep", "Edit").forEach {
            assertTrue(Regex("\\b$it\\b").containsMatchIn(instructions), "instructions don't mention $it:\n$instructions")
        }
    }

    @Test
    fun `tells agents to retry on a readiness timeout instead of falling back`() {
        assertTrue("retry" in instructions.lowercase(), instructions)
        assertTrue("don't fall back" in instructions.lowercase(), instructions)
    }

    @Test
    fun `names the project root`() {
        assertTrue(root.toString() in instructions, instructions)
    }
}
