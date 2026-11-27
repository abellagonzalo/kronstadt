package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.Tool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kronstadt.core.application.Edit
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindUsages
import kronstadt.core.application.GetDefinition
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.RenameSymbol
import kronstadt.core.application.WriteCalls
import kronstadt.core.domain.Readiness
import kronstadt.core.port.CodeIntelligence
import kronstadt.workspace.LocalFileStore
import java.lang.reflect.Proxy
import java.nio.file.Path
import kotlin.time.Duration

/**
 * Every Kronstadt tool registered on an MCP [Server] over the files under [root] and [intelligence], called as an agent
 * calls them. The one place server unit tests build the tools; a test supplies only the files and the fake.
 */
internal class ToolHarness(
    root: Path,
    intelligence: CodeIntelligence,
    /** How long the use cases that wait for readiness wait. */
    readinessTimeout: Duration = Readiness.DEFAULT_TIMEOUT,
    /** Where the server's own classes are loaded from: the test's own, or an install that can change under it. */
    install: ClassLoader = KronstadtTools::class.java.classLoader,
) {
    private val server = Server(
        Implementation(name = "kronstadt-test", version = "0"),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
    ).also { server ->
        val files = LocalFileStore(root)
        // Shared, as in Main.kt: a rename drops the Rejected Edit an Edit kept.
        val writeCalls = WriteCalls()
        val readStructure = ReadStructure(files, intelligence)
        val findSymbol = FindSymbol(files, intelligence, readinessTimeout)
        val readSymbol = ReadSymbol(files, intelligence, readinessTimeout)
        val findUsages = FindUsages(files, intelligence, readinessTimeout)
        val getDefinition = GetDefinition(files, intelligence, readinessTimeout)
        val edit = Edit(files, intelligence, readinessTimeout, writeCalls = writeCalls)
        val renameSymbol = RenameSymbol(files, intelligence, readinessTimeout, writeCalls = writeCalls)
        // Through reflection, so the tools can come from another class loader than the test's: one path for both.
        val tools = install.loadClass(KronstadtTools::class.java.name)
        val instance = tools.constructors.single()
            .newInstance(readStructure, findSymbol, readSymbol, findUsages, getDefinition, edit, renameSymbol)
        tools.getMethod("registerOn", Server::class.java).invoke(instance, server)
    }

    /** No tool handler talks back to the client. */
    private val connection = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ClientConnection::class.java)) { _, method, _ ->
        error("unexpected call to ClientConnection.${method.name}")
    } as ClientConnection

    /** The names of every registered tool. */
    val toolNames: Set<String> get() = server.tools.keys

    /** The registered tool [name], with its description and input schema as an agent sees them. */
    fun tool(name: String): Tool = registered(name).tool

    /** Calls [tool] with [arguments], JSON as an agent writes it; whether it failed, and its text. */
    suspend fun call(tool: String, arguments: String): Pair<Boolean, String> {
        val request = CallToolRequest(CallToolRequestParams(tool, Json.parseToJsonElement(arguments) as JsonObject))
        val result = registered(tool).handler(connection, request)
        return (result.isError == true) to result.content.joinToString("\n") { (it as TextContent).text }
    }

    private fun registered(tool: String) = checkNotNull(server.tools[tool]) { "no tool $tool registered" }
}
