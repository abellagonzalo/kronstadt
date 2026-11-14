package kronstadt.core.port

import kronstadt.core.domain.TextDocument
import java.nio.file.Path

/** Access to project files, confined to the project root (one Git worktree per Kronstadt instance). */
public interface FileStore {
    /** Resolves a path given by the agent (absolute or project-relative). Returns null if it is outside the project. */
    public fun resolve(path: String): Path?

    /** Reads [file], or returns null if it does not exist. */
    public suspend fun read(file: Path): TextDocument?

    /** Path as it should be shown to the agent: relative to the project root. */
    public fun display(file: Path): String
}
