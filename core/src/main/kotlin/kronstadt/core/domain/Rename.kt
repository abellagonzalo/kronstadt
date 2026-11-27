package kronstadt.core.domain

import java.nio.file.Path

/** Whether the name at a position can be renamed, as the code intelligence sees it before renaming. */
public sealed interface RenamePreparation {
    /** The name at [range], currently [placeholder], can be renamed. */
    public data class Renameable(val range: TextRange, val placeholder: String) : RenamePreparation

    /** It can't; [reason] is the code intelligence's own words, e.g. "This element cannot be renamed". */
    public data class Refused(val reason: String) : RenamePreparation
}

/** What renaming a Symbol changes across the project, or why it can't. */
public sealed interface Rename {
    /** The changes to apply, in order: a [FileChange.Moved] file's occurrences name its old path. */
    public data class Changes(val changes: List<FileChange>) : Rename

    /** It can't be renamed; [reason] is the code intelligence's own words. */
    public data class Refused(val reason: String) : Rename
}

/** One change a rename makes to a project file. */
public sealed interface FileChange {
    /**
     * The [occurrences] of the old name in [file] the rename changes, as ranges in its text before the rename, never
     * overlapping. Each is replaced by the new name as written, backticks included.
     */
    public data class Edited(val file: Path, val occurrences: List<TextRange>) : FileChange

    /** [from] is renamed to [to], e.g. a class named like its file. */
    public data class Moved(val from: Path, val to: Path) : FileChange
}

/** Replace [range] with [newText]. */
public data class TextEdit(val range: TextRange, val newText: String)
