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
import kronstadt.core.application.Edit
import kronstadt.core.application.FindSymbol
import kronstadt.core.application.FindUsages
import kronstadt.core.application.GetDefinition
import kronstadt.core.application.ReadStructure
import kronstadt.core.application.ReadSymbol
import kronstadt.core.application.RenameSymbol
import kronstadt.core.application.WriteLock
import kronstadt.core.domain.Readiness
import kronstadt.lsp.LspCodeIntelligence
import kronstadt.workspace.IndexDirectories
import kronstadt.workspace.IndexSeeds
import kronstadt.workspace.LocalFileStore
import kronstadt.workspace.SeedRefresh
import kronstadt.workspace.Seeding
import kronstadt.workspace.WorktreeIndex
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlin.time.Duration

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
    val indexes = IndexDirectories.default()
    val seeds = IndexSeeds(indexes)
    val worktreeIndex = CompletableDeferred<WorktreeIndex>()

    // Start kotlin-lsp eagerly in the background, so its import and indexing overlap the agent's first turns.
    // Failures surface as tool errors, not a crashed server.
    val languageServer = async(Dispatchers.IO) {
        try {
            try {
                indexes.prune()
            } catch (e: Exception) {
                // Pruning only frees disk; it must never stop kotlin-lsp from starting.
                System.err.println("kronstadt: could not prune ${indexes.cacheRoot}: ${e.message}")
            }
            val index = seeds.prepare(files.root)
            when (val seeding = index.seeding) {
                Seeding.Seeded -> System.err.println("kronstadt: started ${index.dir} from the repo's Index Seed")
                Seeding.AlreadyIndexed -> {}
                is Seeding.Cold -> System.err.println("kronstadt: indexing cold: ${seeding.reason}")
            }
            worktreeIndex.complete(index)
            Result.success(LspCodeIntelligence.start(config.languageServer, files.root, index.dir))
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
        instructions = serverInstructions(files.root),
    )
    val intelligence = DeferredCodeIntelligence(languageServer)
    // One per instance: write tools run one at a time (ADR 0003).
    val writeLock = WriteLock()
    KronstadtTools(
        ReadStructure(files, intelligence),
        FindSymbol(files, intelligence, config.readinessTimeout),
        ReadSymbol(files, intelligence, config.readinessTimeout),
        FindUsages(files, intelligence, config.readinessTimeout),
        GetDefinition(files, intelligence, config.readinessTimeout),
        Edit(files, intelligence, config.readinessTimeout, config.validationTimeout, writeLock),
        RenameSymbol(files, intelligence, config.readinessTimeout, config.validationTimeout, writeLock),
    ).registerOn(server)

    val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
    val session = server.createSession(transport)
    val closed = CompletableDeferred<Unit>()
    session.onClose { closed.complete(Unit) }
    closed.await()

    if (languageServer.isCompleted) {
        languageServer.await().onSuccess { shutDown(it, seeds, worktreeIndex.await()) }
    } else {
        languageServer.cancel()
    }
}

/**
 * Stops kotlin-lsp and, if it had a complete index and exited cleanly, makes that index its repo's Index Seed.
 * Seeding failures only cost the next worktree a cold index, so they are logged, never thrown.
 */
private suspend fun shutDown(languageServer: LspCodeIntelligence, seeds: IndexSeeds, index: WorktreeIndex) {
    val ready = languageServer.awaitReady(Duration.ZERO) == Readiness.Ready
    val clean = languageServer.shutdown()
    val outcome = when {
        !ready -> SeedRefresh.Skipped("kotlin-lsp had not finished indexing")
        !clean -> SeedRefresh.Skipped("kotlin-lsp did not exit cleanly")
        else -> try {
            seeds.refresh(index)
        } catch (e: Exception) {
            SeedRefresh.Skipped(e.message ?: e.toString())
        }
    }
    when (outcome) {
        SeedRefresh.Refreshed -> System.err.println("kronstadt: refreshed the Index Seed from ${index.dir}")
        is SeedRefresh.Skipped -> System.err.println("kronstadt: Index Seed not refreshed: ${outcome.reason}")
    }
}
