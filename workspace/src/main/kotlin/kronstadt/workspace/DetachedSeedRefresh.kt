package kronstadt.workspace

import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.time.Instant
import kotlin.time.Duration.Companion.seconds

/** What starting a detached Index Seed refresh did. */
public sealed interface SeedRefreshStart {
    /** The refresh runs in a process of its own, which logs what it did to [log]. */
    public data class Started(val log: Path) : SeedRefreshStart

    /** Nothing was started, for [reason]. */
    public data class Skipped(val reason: String) : SeedRefreshStart
}

/**
 * Refreshes the Index Seed from a process of its own, which outlives Kronstadt.
 *
 * Claude Code ends an MCP server with SIGINT, then SIGTERM 0.1 s later and SIGKILL 0.4 s after that, and signals only
 * the server's own process (#52). That is too soon to wait for kotlin-lsp to exit and to clone its index, so Kronstadt
 * starts this process and leaves: a child, which Claude Code doesn't signal, run under `nohup` so that closing the
 * terminal doesn't end it either. It waits for kotlin-lsp to exit, then refreshes the seed with [IndexSeeds.refresh],
 * whose atomic swap leaves the previous seed whole if this process is killed too.
 */
public object DetachedSeedRefresh {
    /** The log of the last detached refresh, next to the seed slot. */
    public const val LOG_FILE: String = "last-refresh.log"

    /** How long the refresh waits for kotlin-lsp to exit; it took about 0.5 s on the lsp fixture. */
    private val EXIT_WAIT = 10.seconds

    /**
     * Starts refreshing [index]'s seed, once [languageServer] has exited, in a JVM like this one with its classpath,
     * and returns at once. Call only after kotlin-lsp answered `shutdown`, with a complete index.
     */
    public fun start(languageServer: ProcessHandle, index: WorktreeIndex): SeedRefreshStart {
        val slot = index.seedSlot ?: return SeedRefreshStart.Skipped("not a git repository")
        val java = ProcessHandle.current().info().command().orElse(null)
            ?: return SeedRefreshStart.Skipped("could not tell which java runs Kronstadt")
        val log = slot.resolveSibling(LOG_FILE)
        return try {
            ProcessBuilder(
                "nohup", java, "--enable-native-access=ALL-UNNAMED", "-Xmx64m",
                "-cp", System.getProperty("java.class.path"),
                DetachedSeedRefresh::class.java.name,
                languageServer.pid().toString(), startedAt(languageServer), index.dir.toString(), slot.toString(),
            )
                .redirectInput(ProcessBuilder.Redirect.from(File("/dev/null")))
                .redirectOutput(log.toFile())
                .redirectErrorStream(true)
                .start()
            SeedRefreshStart.Started(log)
        } catch (e: IOException) {
            SeedRefreshStart.Skipped("could not start the refresh: ${e.message}")
        }
    }

    /**
     * The detached process: `<kotlin-lsp pid> <its start instant> <index dir> <seed slot>`. Its output goes to [LOG_FILE].
     * kotlin-lsp may have exited before this JVM started, and its pid gone to another process: only a process started at
     * the same instant is kotlin-lsp.
     */
    @JvmStatic
    public fun main(args: Array<String>) {
        val (pid, started, dir, slot) = args
        val index = WorktreeIndex(Path.of(dir), Seeding.AlreadyIndexed, Path.of(slot))
        val seeds = IndexSeeds(IndexDirectories.default())
        println("${Instant.now()} kronstadt: waiting for kotlin-lsp (pid $pid) to exit")
        val outcome = try {
            val languageServer = ProcessHandle.of(pid.toLong()).filter { startedAt(it) == started }
            if (languageServer.isPresent) seeds.refreshAfterExit(languageServer.get(), index, EXIT_WAIT) else seeds.refresh(index)
        } catch (e: Exception) {
            SeedRefresh.Skipped(e.message ?: e.toString())
        }
        when (outcome) {
            SeedRefresh.Refreshed -> println("${Instant.now()} kronstadt: refreshed the Index Seed from $dir")
            is SeedRefresh.Skipped -> println("${Instant.now()} kronstadt: Index Seed not refreshed: ${outcome.reason}")
        }
    }

    /** When [process] started, to tell it from a later process given the same pid; "unknown" where the OS doesn't say. */
    private fun startedAt(process: ProcessHandle): String =
        process.info().startInstant().orElse(null)?.toString() ?: "unknown"
}
