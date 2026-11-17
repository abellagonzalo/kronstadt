package kronstadt.evaluation

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Path

/** A write to an existing `.kt` file that went around Kronstadt; [how] is the shell command or `Write after rm`. */
internal data class Circumvention(val file: Path, val how: String, val call: ToolCall)

/**
 * Finds a session's circumventions inside its project root: Bash writes to existing `.kt` files (`sed -i`, `perl -i`,
 * `>`/`>>` redirection, `tee`, `cp`/`mv` onto one) and `Write` recreating a `.kt` file the agent deleted.
 *
 * Whether a file existed is judged from the session itself: in-place editors need an existing file; any other write
 * counts only if the path was mentioned by an earlier call or result, so a heredoc creating a new file does not.
 */
internal object Circumventions {
    fun find(session: Session): List<Circumvention> {
        val found = mutableListOf<Circumvention>()
        val seen = mutableSetOf<Path>()
        val deleted = mutableSetOf<Path>()
        for (call in session.calls) {
            fun inProject(path: Path) = path.startsWith(session.cwd)
            when (call.name) {
                "Bash" -> for ((segment, dir) in Shell.located(call.string("command").orEmpty(), call.cwd)) {
                    for ((path, needsExisting) in writes(segment)) {
                        val file = dir.resolve(path).normalize()
                        if (inProject(file) && (needsExisting || file in seen)) found.add(Circumvention(file, segment.text, call))
                        seen.add(file)
                    }
                    if (segment.program == "rm" || segment.program == "git" && segment.args.getOrNull(1) == "rm") {
                        segment.args.filter(Kotlin::isKtPath).forEach { deleted.add(dir.resolve(it).normalize()) }
                    }
                }
                "Write" -> call.string("file_path")?.takeIf(Kotlin::isKtPath)?.let { call.cwd.resolve(it).normalize() }?.let { file ->
                    if (inProject(file) && deleted.remove(file)) found.add(Circumvention(file, "Write after rm", call))
                }
            }
            (strings(call.input) + call.result.orEmpty()).flatMap(Kotlin::ktPaths).forEach { seen.add(call.cwd.resolve(it).normalize()) }
        }
        return found
    }

    /** The `.kt` files [segment] writes, each with whether the write only works on an existing file. */
    private fun writes(segment: Shell.Segment): List<Pair<String, Boolean>> {
        val args = segment.args
        val redirected = segment.redirects.filter { it.writes && Kotlin.isKtPath(it.target) }.map { it.target to false }
        val byProgram = when (args.firstOrNull()) {
            "sed", "perl" -> if (segment.editsInPlace) args.ktFiles(true) else emptyList()
            "tee" -> args.ktFiles(false)
            "cp", "mv" -> args.lastOrNull()?.takeIf { args.size > 2 && Kotlin.isKtPath(it) }?.let { listOf(it to false) }.orEmpty()
            else -> emptyList()
        }
        return byProgram + redirected
    }

    /** Every string value in a tool input, however deeply nested. */
    private fun strings(element: JsonElement): List<String> = when (element) {
        is JsonPrimitive -> if (element.isString) listOf(element.content) else emptyList()
        is JsonArray -> element.flatMap(::strings)
        is JsonObject -> element.values.flatMap(::strings)
    }

    private fun List<String>.ktFiles(needsExisting: Boolean) = drop(1).filter(Kotlin::isKtPath).map { it to needsExisting }
}
