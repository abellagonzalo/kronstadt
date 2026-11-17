package kronstadt.evaluation

/**
 * Informational: how often the steering hook denied a built-in edit, and what the same agent did next.
 *
 * A denial is a failed `Edit`, `MultiEdit` or `Write` whose message routes to `kt_edit`, as the hook's deny reason does.
 * What followed is the next call in the same thread: a Kronstadt tool by name, `circumvention (Bash)` or
 * `circumvention (Write)`, another tool by name, or `(nothing)`.
 */
internal object HookDenials {
    private val EDIT_TOOLS = setOf("Edit", "MultiEdit", "Write")

    fun followUps(session: Session, circumventions: List<Circumvention>): Map<String, Int> {
        val circumventing = circumventions.map { it.call }.toSet()
        return session.calls.withIndex()
            .filter { (_, call) -> call.name in EDIT_TOOLS && call.isError && "kt_edit" in call.result.orEmpty() }
            .map { (index, denied) ->
                val next = session.calls.drop(index + 1).firstOrNull { it.thread == denied.thread }
                when {
                    next == null -> "(nothing)"
                    next in circumventing -> "circumvention (${next.name})"
                    else -> KronstadtTool.of(next.name) ?: next.name
                }
            }
            .groupingBy { it }.eachCount()
    }
}
