package kronstadt.benchmark

/** How much a Benchmark spends: which tasks it runs, and how many times per task and Arm. */
internal enum class Level(val label: String, val runsPerTaskAndArm: Int) {
    LOW("low", 1),
    MEDIUM("medium", 3),
    HIGH("high", 5),
    ;

    /** The tasks this level runs: every task, or for [LOW] the first of each kind by id, which needs one of each. */
    fun tasks(all: List<BenchmarkTask>): List<BenchmarkTask> {
        if (this != LOW) return all
        return TaskKind.entries.map { kind ->
            all.sortedBy { it.id }.firstOrNull { it.kind == kind }
                ?: throw IllegalArgumentException("level $label needs a Benchmark Task of kind ${kind.label}")
        }
    }

    companion object {
        fun of(label: String): Level = entries.firstOrNull { it.label == label }
            ?: throw IllegalArgumentException("unknown level '$label'; expected low, medium or high")
    }
}

/** With Kronstadt, or without it. */
internal enum class Arm(val label: String) {
    WITH_KRONSTADT("with Kronstadt"),
    WITHOUT_KRONSTADT("without Kronstadt"),
}

/** One `claude` session of a Benchmark: [task] in [arm], its [run]th time (from 1). */
internal data class PlannedSession(val task: BenchmarkTask, val arm: Arm, val run: Int) {
    /** Names the session's run worktree and raw output, e.g. `nav-1-with-2`. */
    val name: String get() = "${task.id}-${if (arm == Arm.WITH_KRONSTADT) "with" else "without"}-$run"
}

/**
 * The sessions in the order they run, one at a time: [runs] rounds over [tasks], each task with Kronstadt and then
 * without it, so the arms alternate and slow drift on the machine hits both alike.
 */
internal fun schedule(tasks: List<BenchmarkTask>, runs: Int): List<PlannedSession> =
    (1..runs).flatMap { run -> tasks.flatMap { task -> Arm.entries.map { arm -> PlannedSession(task, arm, run) } } }
