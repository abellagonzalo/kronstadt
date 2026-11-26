package kronstadt.benchmark

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText

/** What a Benchmark Task asks of the agent; the task's `kind` file holds the [label]. */
internal enum class TaskKind(val label: String) {
    NAVIGATION("navigation"),
    LOCAL_EDIT("local-edit"),
    REFACTOR("refactor"),
}

/** A Benchmark Task: one fixed task, its [prompt], together with its success check, the executable `check` in [dir]. */
internal data class BenchmarkTask(val id: String, val kind: TaskKind, val prompt: String, val dir: Path)

internal object BenchmarkTasks {
    /** Every Benchmark Task under [root]: each directory holding `prompt.md`, `kind` and an executable `check`, by id. */
    fun load(root: Path): List<BenchmarkTask> {
        val dirs = Files.list(root).use { entries -> entries.filter { Files.isDirectory(it) }.toList() }
        return dirs.sortedBy { it.fileName.toString() }.map { dir ->
            fun file(name: String) = dir.resolve(name).also { require(it.exists()) { "Benchmark Task ${dir.fileName} has no $name" } }
            val check = file("check")
            require(Files.isExecutable(check)) { "$check isn't executable" }
            val kindLabel = file("kind").readText().trim()
            val kind = TaskKind.entries.firstOrNull { it.label == kindLabel }
                ?: throw IllegalArgumentException("${dir.fileName}: unknown kind '$kindLabel'")
            BenchmarkTask(dir.fileName.toString(), kind, file("prompt.md").readText().trim(), dir.toAbsolutePath())
        }
    }
}
