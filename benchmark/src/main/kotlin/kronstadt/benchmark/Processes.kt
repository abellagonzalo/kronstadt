package kronstadt.benchmark

import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** How a process ended: its exit code, null if it was killed at the timeout, and how long it ran. */
internal data class ProcessRun(val exitCode: Int?, val wallClock: Duration) {
    val timedOut: Boolean get() = exitCode == null
}

internal object Processes {
    /**
     * Runs [command] in [dir] until it exits or [timeout] passes, then kills it with all its descendants. Its stdout goes
     * to [stdout], its stderr to [stderr] or, if null, to [stdout] too. [stdin] is written to it, or it gets
     * `/dev/null`. [environment], if given, replaces this process's environment.
     */
    fun run(
        command: List<String>,
        dir: Path,
        stdout: Path,
        stderr: Path? = null,
        stdin: String? = null,
        environment: Map<String, String>? = null,
        timeout: Duration,
    ): ProcessRun {
        val builder = ProcessBuilder(command).directory(dir.toFile()).redirectOutput(stdout.toFile())
        if (stderr == null) builder.redirectErrorStream(true) else builder.redirectError(stderr.toFile())
        if (stdin == null) builder.redirectInput(File("/dev/null"))
        if (environment != null) builder.environment().run { clear(); putAll(environment) }
        val started = TimeSource.Monotonic.markNow()
        val process = builder.start()
        if (stdin != null) {
            // A check that never reads its stdin mustn't block us once the pipe is full.
            thread(isDaemon = true) { runCatching { process.outputStream.use { it.write(stdin.toByteArray()) } } }
        }
        if (process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            return ProcessRun(process.exitValue(), started.elapsedNow())
        }
        val wallClock = started.elapsedNow()
        kill(process.toHandle())
        return ProcessRun(null, wallClock)
    }

    /** Runs [command] in [dir] and returns its stdout, trimmed; throws with its stderr if it fails or outlasts [timeout]. */
    fun output(vararg command: String, dir: Path? = null, timeout: Duration = 15.minutes): String {
        val process = ProcessBuilder(*command).apply { if (dir != null) directory(dir.toFile()) }.start()
        var stderr = ""
        var stdout = ""
        val readers = listOf(
            thread { stderr = process.errorStream.bufferedReader().readText() },
            thread { stdout = process.inputStream.bufferedReader().readText() },
        )
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            kill(process.toHandle())
            error("${command.joinToString(" ")} didn't finish within $timeout")
        }
        readers.forEach { it.join() }
        check(process.exitValue() == 0) { "${command.joinToString(" ")} failed (exit ${process.exitValue()}): ${stderr.trim()}" }
        return stdout.trim()
    }

    /** Ends [process] and its descendants: SIGTERM first, so `claude` can end its MCP servers, then SIGKILL. */
    private fun kill(process: ProcessHandle) {
        val all = process.descendants().toList() + process
        all.forEach { it.destroy() }
        val deadline = TimeSource.Monotonic.markNow() + 10.seconds
        while (all.any { it.isAlive } && deadline.hasNotPassedNow()) Thread.sleep(100)
        all.filter { it.isAlive }.forEach { it.destroyForcibly() }
    }
}
