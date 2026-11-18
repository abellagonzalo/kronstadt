package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kronstadt.core.application.Edit
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindUsages
import kronstadt.core.application.GetDefinition
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.RenameSymbol
import kronstadt.core.port.CodeIntelligence
import kronstadt.workspace.LocalFileStore
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/** The server instructions route agents to every tool Kronstadt registers, and away from built-in fallbacks. */
class InstructionsTest {
    private val root: Path = Files.createTempDirectory("kt-instructions").toRealPath()

    private val registeredTools: Set<String> = Server(
        Implementation(name = "kronstadt-test", version = "0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).also { server ->
        val files = LocalFileStore(root)
        val intelligence = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(CodeIntelligence::class.java)) { _, method, _ ->
            error("unexpected call to CodeIntelligence.${method.name}")
        } as CodeIntelligence
        KronstadtTools(
            ReadStructure(files, intelligence),
            FindSymbol(files, intelligence),
            ReadSymbol(files, intelligence),
            FindUsages(files, intelligence),
            GetDefinition(files, intelligence),
            Edit(files, intelligence),
            RenameSymbol(files, intelligence),
        ).registerOn(server)
    }.tools.keys

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
