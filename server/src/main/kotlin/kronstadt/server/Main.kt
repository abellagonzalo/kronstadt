package kronstadt.server

import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
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
import kronstadt.lsp.LspCodeIntelligence
import kronstadt.workspace.DetachedSeedRefresh
import kronstadt.workspace.IndexDirectories
import kronstadt.workspace.IndexSeeds
import kronstadt.workspace.LocalFileStore
import kronstadt.workspace.SeedRefreshStart
import kronstadt.workspace.Seeding
import kronstadt.workspace.WorktreeIndex
import java.io.PrintStream
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

internal const val VERSION = "0.1.0"

/** How long Kronstadt waits for kotlin-lsp to answer `shutdown` as it ends. It took 6 ms; SIGKILL comes 0.5 s after SIGINT. */
private val HAND_OFF_ANSWER_WAIT = 200.milliseconds

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
            // Only the instance holding the claim seeds, starts kotlin-lsp and so refreshes the seed as it ends. The
            // claim is held until the process exits.
            Result.success(
                startUp(
                    files.root,
                    claim = { indexes.claim(files.root) },
                    prune = indexes::prune,
                    prepare = {
                        val index = seeds.prepare(files.root)
                        when (val seeding = index.seeding) {
                            Seeding.Seeded -> System.err.println("kronstadt: started ${index.dir} from the repo's Index Seed")
                            Seeding.AlreadyIndexed -> {}
                            is Seeding.Cold -> System.err.println("kronstadt: indexing cold: ${seeding.reason}")
                        }
                        worktreeIndex.complete(index)
                        index
                    },
                    start = { index -> LspCodeIntelligence.start(config.languageServer, files.root, index.dir) },
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            System.err.println("kronstadt: ${e.message}")
            Result.failure(e)
        }
    }

    // Claude Code never closes stdin: it ends Kronstadt with SIGINT, SIGTERM and SIGKILL within 0.5 s (#52). A
    // shutdown hook runs on those signals and on the exit after a closed stdin alike.
    Runtime.getRuntime().addShutdownHook(Thread { handOff(languageServer, worktreeIndex) })

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
        Edit(files, intelligence, config.readinessTimeout, config.validationTimeout, writeLock, collateralTimeout = config.collateralTimeout),
        RenameSymbol(files, intelligence, config.readinessTimeout, config.validationTimeout, writeLock),
    ).registerOn(server)

    val transport = StdioServerTransport(System.`in`.asSource().buffered(), protocolOut.asSink().buffered())
    val session = server.createSession(transport)
    val closed = CompletableDeferred<Unit>()
    session.onClose { closed.complete(Unit) }
    closed.await()
    // The shutdown hook ends a started kotlin-lsp.
    if (!languageServer.isCompleted) languageServer.cancel()
}

/**
 * Ends kotlin-lsp and, if its index is complete, hands the Index Seed refresh to [DetachedSeedRefresh], which waits
 * for kotlin-lsp to exit and clones its index: Claude Code kills Kronstadt 0.5 s after signalling it, too soon to do
 * that here. Seeding failures only cost the next worktree a cold index, so they are logged, never thrown.
 */
private fun handOff(languageServer: Deferred<Result<LspCodeIntelligence>>, worktreeIndex: Deferred<WorktreeIndex>) {
    if (!languageServer.isCompleted || languageServer.isCancelled) return
    val started = runBlocking { languageServer.await() }.getOrNull() ?: return
    val indexComplete = started.indexComplete
    val answered = started.requestExit(HAND_OFF_ANSWER_WAIT)
    val message = handOffMessage(indexComplete, answered) {
        DetachedSeedRefresh.start(started.processHandle, runBlocking { worktreeIndex.await() })
    }
    System.err.println("kronstadt: $message")
}

/**
 * What [handOff] did with the Index Seed: [refresh] runs only if kotlin-lsp's index was complete when Kronstadt began
 * ending it (see [LspCodeIntelligence.indexComplete]; no readiness settle period, #79) and it answered `shutdown`.
 */
internal fun handOffMessage(indexComplete: Boolean, answeredShutdown: Boolean, refresh: () -> SeedRefreshStart): String =
    when {
        !indexComplete -> "Index Seed not refreshed: kotlin-lsp had not finished indexing"
        !answeredShutdown -> "Index Seed not refreshed: kotlin-lsp did not answer shutdown"
        else -> when (val start = refresh()) {
            is SeedRefreshStart.Started -> "refreshing the Index Seed once kotlin-lsp exits, in the background; see ${start.log}"
            is SeedRefreshStart.Skipped -> "Index Seed not refreshed: ${start.reason}"
        }
    }
