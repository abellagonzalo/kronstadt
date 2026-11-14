package kronstadt.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.FileStore
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path

/** [FileStore] over the local disk, confined to [projectRoot]. */
public class LocalFileStore(projectRoot: Path) : FileStore {
    public val root: Path = projectRoot.toRealPath()

    override fun resolve(path: String): Path? {
        val candidate = root.resolve(path).normalize()
        if (!candidate.startsWith(root)) return null
        // Follow symlinks for files that exist, so a link inside the project cannot point outside it.
        if (Files.exists(candidate) && !candidate.toRealPath().startsWith(root)) return null
        return candidate
    }

    override suspend fun read(file: Path): TextDocument? = withContext(Dispatchers.IO) {
        try {
            if (Files.isRegularFile(file)) TextDocument(Files.readString(file)) else null
        } catch (_: NoSuchFileException) {
            null
        }
    }

    override fun display(file: Path): String = root.relativize(file).toString()
}
