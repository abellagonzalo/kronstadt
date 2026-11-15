package kronstadt.core.port

import kronstadt.core.domain.TextDocument
import java.nio.file.Path

/** Access to project files, confined to the project root (one Git worktree per Kronstadt instance). */
public interface FileStore {
    /** Resolves a path given by the agent (absolute or project-relative). Returns null if it is outside the project. */
    public fun resolve(path: String): Path?

    /** Reads [file], or returns null if it does not exist. */
    public suspend fun read(file: Path): TextDocument?

    /** Reads [file] with a hash of the exact bytes read, which a later [writeIfUnchanged] checks; null if it doesn't exist. */
    public suspend fun readHashed(file: Path): HashedDocument?

    /**
     * Write-back (ADR 0003): replaces [file]'s content with [document], but only if the file still hashes to [basedOn].
     * Writes through a temp file and an atomic rename that keeps the file's permissions, so a crash never leaves it
     * half-written.
     */
    public suspend fun writeIfUnchanged(file: Path, basedOn: ContentHash, document: TextDocument): WriteResult

    /** Path as it should be shown to the agent: relative to the project root. */
    public fun display(file: Path): String
}

/** Identifies a file's exact bytes; equal hashes mean the file didn't change in between. */
@JvmInline
public value class ContentHash(public val value: String)

public data class HashedDocument(val document: TextDocument, val hash: ContentHash)

public sealed interface WriteResult {
    public data object Written : WriteResult

    /** The file no longer hashes to what the write was based on (or is gone); nothing was written. */
    public data object ChangedOnDisk : WriteResult
}
