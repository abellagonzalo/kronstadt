package kronstadt.lsp

import kronstadt.core.domain.FileChange
import kronstadt.core.domain.PLAIN_NAME
import kronstadt.core.domain.Position
import kronstadt.core.domain.Rename
import kronstadt.core.domain.TextDocument
import kronstadt.core.domain.TextEdit
import kronstadt.core.domain.TextRange
import kronstadt.core.domain.importLines
import kronstadt.core.domain.nameAsWritten
import java.nio.file.Path

/** kotlin-lsp's answer to `rename` as it sent it, before [RenameAnswer] reads it. */
internal sealed interface ReportedRename {
    /** Its changes, in order: a [ReportedChange.Moved] file's edits name its old path. */
    data class Changes(val changes: List<ReportedChange>) : ReportedRename

    /** Refused, for [reason]: kotlin-lsp's own words, or why its answer can't be used. */
    data class Refused(val reason: String) : ReportedRename
}

/** One change in kotlin-lsp's rename answer. */
internal sealed interface ReportedChange {
    /** Raw [edits] to [file]'s text, each against the text before any of them. */
    data class Edited(val file: Path, val edits: List<TextEdit>) : ReportedChange

    /** [from] is renamed to [to]. */
    data class Moved(val from: Path, val to: Path) : ReportedChange
}

/**
 * Reads kotlin-lsp's rename answer as the port's: the occurrences of the old name it changes in each file, and its file
 * moves. kotlin-lsp 271.2085.0 sends minimal diffs, often several to one name and not aligned with it (#85), and also
 * deletes imports that were already unused in the files it touches. Here the diffs become whole-name occurrences and the
 * import deletions are left out; any other edit refuses the rename.
 */
internal object RenameAnswer {
    /**
     * The name at [position] in [document], as the rename's old name: in backticks if [position] is just inside them
     * (without them), else the plain identifier around [position]; null if there is none.
     */
    fun nameAt(document: TextDocument, position: Position): String? {
        val text = document.text
        val offset = document.offsetOf(position)
        if (text.getOrNull(offset - 1) == '`') {
            val close = text.indexOf('`', offset).takeIf { it > offset } ?: return null
            return text.substring(offset, close).takeUnless { '\n' in it || '\r' in it }
        }
        return PLAIN_NAME.findAll(text.substring(document.offsetOf(Position(position.line, 0)), document.offsetOf(Position(position.line, Int.MAX_VALUE))))
            .firstOrNull { position.character in it.range.first..it.range.last + 1 }?.value
    }

    /**
     * The port's answer for [reported], renaming [oldName] to [newName] as written. [textOf] gives a file's text before the
     * rename, as kotlin-lsp sees it; null if there's no such file. Later edits that name a moved file's new path are read
     * against its old one.
     */
    suspend fun read(reported: ReportedRename, oldName: String, newName: String, textOf: suspend (Path) -> TextDocument?): Rename {
        val changes = when (reported) {
            is ReportedRename.Refused -> return Rename.Refused(reported.reason)
            is ReportedRename.Changes -> reported.changes
        }
        val written = nameAsWritten(oldName)
        val movedFrom = mutableMapOf<Path, Path>()
        val edited = mutableSetOf<Path>()
        return Rename.Changes(
            changes.map { change ->
                when (change) {
                    is ReportedChange.Moved -> {
                        movedFrom[change.to] = change.from
                        FileChange.Moved(change.from, change.to)
                    }
                    is ReportedChange.Edited -> {
                        val source = movedFrom[change.file] ?: change.file
                        if (!edited.add(source)) return Rename.Refused("kotlin-lsp would change ${change.file} in two separate steps")
                        val before = textOf(source) ?: return Rename.Refused("kotlin-lsp would change ${change.file}, which doesn't exist")
                        when (val read = occurrences(change.file, before, change.edits, written, newName)) {
                            is Read.Occurrences -> FileChange.Edited(change.file, read.ranges)
                            is Read.Unexpected -> return Rename.Refused(read.reason)
                        }
                    }
                }
            },
        )
    }

    /** One file's edits, read: the occurrences they change, or why they can't be read as such. */
    private sealed interface Read {
        /** The occurrences of the old name the edits change, as [ranges] in the file's text before the rename. */
        class Occurrences(val ranges: List<TextRange>) : Read

        /** An edit is neither a change to an occurrence nor an import's removal, as [reason] says. */
        class Unexpected(val reason: String) : Read
    }

    /**
     * The occurrences of [written] in [before], [file]'s text, that [edits] change. Their text with each occurrence
     * replaced by [newName] must be what [edits] make of it, but for whole lines of the header's imports the edits delete,
     * and blank lines next to them: those deletions are left out, with any occurrence on such a line. Anything else is
     * unexpected.
     */
    private fun occurrences(file: Path, before: TextDocument, edits: List<TextEdit>, written: Regex, newName: String): Read {
        // An insertion goes before a replacement that starts where it does, so applied last to first it stays in front.
        val sorted = edits.sortedWith(compareBy({ it.range.start }, { it.range.end }))
        if (sorted.zipWithNext().any { (first, second) -> second.range.start < first.range.end }) {
            return Read.Unexpected("kotlin-lsp sent overlapping edits to ${file.fileName}")
        }
        val after = sorted.asReversed().fold(before) { text, edit -> text.replace(edit.range, edit.newText) }
        val changed = written.findAll(before.text)
            .map { TextRange(before.positionOf(it.range.first), before.positionOf(it.range.last + 1)) }
            .filter { occurrence -> edits.any { it.changes(occurrence) } }
            .toList()
        val expected = changed.asReversed().fold(before) { text, occurrence -> text.replace(occurrence, newName) }
        // An occurrence never spans lines, so each line of the expected text is the same line of the text before.
        val importLines = before.importLines
        fun deletable(line: Int, text: String) =
            line in importLines || text.isBlank() && (line - 1 in importLines || line + 1 in importLines)
        val deleted = mutableSetOf<Int>()
        var next = 0
        for (line in 0 until expected.lineCount) {
            val text = expected.line(line)
            when {
                next < after.lineCount && text == after.line(next) -> next++
                deletable(line, text) -> deleted += line
                else -> return Read.Unexpected(unexpected(file, before, edits, changed, line))
            }
        }
        if (next < after.lineCount) return Read.Unexpected(unexpected(file, before, edits, changed, line = null))
        return Read.Occurrences(changed.filter { it.start.line !in deleted })
    }

    /**
     * Why the rename is refused: the edits to [line] of [before], [file]'s text, or, without a line or with none there,
     * every edit that changes none of the [changed] occurrences.
     */
    private fun unexpected(file: Path, before: TextDocument, edits: List<TextEdit>, changed: List<TextRange>, line: Int?): String {
        val onLine = line?.let { edits.filter { edit -> line in edit.range.start.line..edit.range.end.line } }.orEmpty()
        val shown = onLine.ifEmpty { edits.filter { edit -> changed.none { edit.changes(it) } } }
        val where = line?.let { "${file.fileName} line ${it + 1}" } ?: file.fileName.toString()
        val what = shown.joinToString { "`${before.slice(it.range)}` -> `${it.newText}` at line ${it.range.start.line + 1}" }
        return "kotlin-lsp's rename would also change $where, which is neither the old name nor an import it removes: $what"
    }

    /** Whether this edit changes [occurrence]: overlaps it, or inserts at it, ends included. */
    private fun TextEdit.changes(occurrence: TextRange): Boolean =
        if (range.start == range.end) range.start in occurrence.start..occurrence.end else range.start < occurrence.end && range.end > occurrence.start
}
