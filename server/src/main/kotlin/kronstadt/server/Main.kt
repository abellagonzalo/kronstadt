package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadSymbol
import kronstadt.lsp.LspCodeIntelligence
import kronstadt.workspace.LocalFileStore
import java.io.PrintStream
import kotlin.system.exitProcess

internal const val VERSION = "0.1.0"

/**
 * Composition root.
 *
 * stdout belongs to the MCP protocol. We keep the real stdout for the transport and redirect [System.out] to stderr,
 * so a stray `println` in any library cannot corrupt the protocol stream.
 */
public fun main(args: Array<String>) {
    System.setProperty("kotlin-logging.logStartupMessage", "false")
    val protocolOut: PrintStream = System.out
    System.setOut(System.err)

    val config = try {
        Config.parse(args)
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    runBlocking { serve(config, protocolOut) }
    // lsp4j and the SDK leave non-daemon threads behind; the session is over, so leave explicitly.
    exitProcess(0)
}

private suspend fun serve(config: Config, protocolOut: PrintStream) = coroutineScope {
    val files = LocalFileStore(config.projectRoot)

    // Start kotlin-lsp eagerly in the background, so its import and indexing overlap the agent's first turns.
    // Failures surface as tool errors, not a crashed server.
    val languageServer = async(Dispatchers.IO) {
        try {
            Result.success(LspCodeIntelligence.start(config.languageServer, files.root))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("kronstadt: ${e.message}")
            Result.failure(e)
        }
    }

    val server = Server(
        Implementation(name = "kronstadt", version = VERSION),
        ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        instructions = "Kronstadt gives symbol-level access to the Kotlin project at ${files.root}. " +
            "Prefer kt_find_symbol over grep to locate declarations, and kt_read_structure and kt_read_symbol over " +
            "reading whole files.",
    )
    val intelligence = DeferredCodeIntelligence(languageServer)
    KronstadtTools(
        ReadStructure(files, intelligence),
        FindSymbol(files, intelligence, config.readinessTimeout),
        ReadSymbol(files, intelligence, config.readinessTimeout),
    ).registerOn(server)

    val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
    val session = server.createSession(transport)
    val closed = CompletableDeferred<Unit>()
    session.onClose { closed.complete(Unit) }
    closed.await()

    if (languageServer.isCompleted) {
        languageServer.await().onSuccess { it.close() }
    } else {
        languageServer.cancel()
    }
}
