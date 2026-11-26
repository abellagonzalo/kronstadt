package kronstadt.core.application

import kronstadt.core.domain.AnalysedFile
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.AnalysisSession
import kronstadt.core.port.FileStore
import java.nio.file.Path

/** [file] with [document] as its text, analysed in this session. */
internal suspend fun AnalysisSession.analyse(files: FileStore, file: Path, document: TextDocument): AnalysedFile =
    AnalysedFile(file, files.display(file), document, documentSymbols(file, document))

/** [file] as read from [files], analysed in this session; null if it doesn't exist. */
internal suspend fun AnalysisSession.analyse(files: FileStore, file: Path): AnalysedFile? =
    files.read(file)?.let { analyse(files, file, it) }

/**
 * Where in-file resolution reads a file's text and `documentSymbol` tree: the file on disk for read tools, the Shadow
 * Workspace's current text for an Edit.
 */
internal fun interface DocumentSource {
    /** [file] as this source has it, analysed; null if it doesn't exist. */
    suspend fun analysed(file: Path): AnalysedFile?

    companion object {
        /** The file as read from [files], analysed in [session]. */
        fun disk(files: FileStore, session: AnalysisSession) = DocumentSource { session.analyse(files, it) }
    }
}
