package kronstadt.benchmark

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlanTest {
    private val tasks = listOf(
        task("edit-1", TaskKind.LOCAL_EDIT),
        task("edit-2", TaskKind.LOCAL_EDIT),
        task("nav-1", TaskKind.NAVIGATION),
        task("nav-2", TaskKind.NAVIGATION),
        task("refactor-1", TaskKind.REFACTOR),
        task("refactor-2", TaskKind.REFACTOR),
    )

    @Test
    fun `low runs the first task of each kind once per arm, medium and high run every task 3 and 5 times`() {
        assertEquals(listOf("nav-1", "edit-1", "refactor-1"), Level.LOW.tasks(tasks).map { it.id })
        assertEquals(6, schedule(Level.LOW.tasks(tasks), Level.LOW.runsPerTaskAndArm).size)
        assertEquals(36, schedule(Level.MEDIUM.tasks(tasks), Level.MEDIUM.runsPerTaskAndArm).size)
        assertEquals(60, schedule(Level.HIGH.tasks(tasks), Level.HIGH.runsPerTaskAndArm).size)
    }

    @Test
    fun `low needs a task of every kind`() {
        val message = runCatching { Level.LOW.tasks(tasks.filter { it.kind != TaskKind.REFACTOR }) }.exceptionOrNull()?.message
        assertEquals("level low needs a Benchmark Task of kind refactor", message)
    }

    @Test
    fun `sessions alternate the arms, starting with Kronstadt, and go round the tasks once per run`() {
        val sessions = schedule(listOf(tasks[0], tasks[2]), runs = 2)

        assertEquals(
            listOf(
                "edit-1-with-1", "edit-1-without-1", "nav-1-with-1", "nav-1-without-1",
                "edit-1-with-2", "edit-1-without-2", "nav-1-with-2", "nav-1-without-2",
            ),
            sessions.map { it.name },
        )
    }

    @Test
    fun `levels are chosen by name`() {
        assertEquals(Level.MEDIUM, Level.of("medium"))
        assertEquals("unknown level 'huge'; expected low, medium or high", runCatching { Level.of("huge") }.exceptionOrNull()?.message)
    }

    @Test
    fun `the repository holds 6 Benchmark Tasks, 2 of each kind, and low picks one of each`() {
        val repositoryTasks = BenchmarkTasks.load(Path.of("tasks"))

        assertEquals(TaskKind.entries.associateWith { 2 }, repositoryTasks.groupingBy { it.kind }.eachCount())
        assertEquals(
            listOf("nav-1-emit-kdoc-callers", "edit-1-skip-unchanged-write", "refactor-1-rename-canonical-name"),
            Level.LOW.tasks(repositoryTasks).map { it.id },
        )
        assertTrue(repositoryTasks.none { "Kronstadt" in it.prompt || "kt_" in it.prompt })
    }
}

internal fun task(id: String, kind: TaskKind = TaskKind.NAVIGATION): BenchmarkTask =
    BenchmarkTask(id, kind, "prompt of $id", Path.of("/tasks", id))
