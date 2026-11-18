package kronstadt.workspace

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.ContentHash
import kronstadt.core.port.FileStore
import kronstadt.core.port.HashedDocument
import kronstadt.core.port.WriteResult
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.security.MessageDigest

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

    override suspend fun readHashed(file: Path): HashedDocument? = withContext(Dispatchers.IO) {
        val bytes = readBytes(file) ?: return@withContext null
        HashedDocument(TextDocument(bytes.decodeToString()), hash(bytes))
    }

    /**
     * Checks the hash, writes a temp file next to [file] (same file system, so the rename can be atomic), copies the
     * permissions over and renames it into place. Write tools run one at a time (ADR 0003), so the window between the
     * check and the rename is only open to writers outside Kronstadt.
     */
    override suspend fun writeIfUnchanged(file: Path, basedOn: ContentHash, document: TextDocument): WriteResult =
        withContext(Dispatchers.IO) {
            val current = readBytes(file)
            if (current == null || hash(current) != basedOn) return@withContext WriteResult.ChangedOnDisk
            val temp = Files.createTempFile(file.parent, ".${file.fileName}.", ".kronstadt")
            try {
                Files.write(temp, document.text.encodeToByteArray())
                if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null) {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file))
                }
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
            WriteResult.Written
        }

    /**
     * Checks the hash, writes a temp file next to [target] with [file]'s permissions and moves it to [target] without
     * replacing anything, then deletes [file]. A crash in between leaves both files, never a half-written one.
     */
    override suspend fun moveIfUnchanged(file: Path, basedOn: ContentHash, target: Path, document: TextDocument): WriteResult =
        withContext(Dispatchers.IO) {
            val current = readBytes(file)
            if (current == null || hash(current) != basedOn) return@withContext WriteResult.ChangedOnDisk
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return@withContext WriteResult.TargetExists
            val temp = Files.createTempFile(target.parent, ".${target.fileName}.", ".kronstadt")
            try {
                Files.write(temp, document.text.encodeToByteArray())
                if (Files.getFileAttributeView(file, PosixFileAttributeView::class.java) != null) {
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(file))
                }
                // Without REPLACE_EXISTING, a target that appeared since the check fails the move instead of being replaced.
                Files.move(temp, target)
            } catch (_: FileAlreadyExistsException) {
                return@withContext WriteResult.TargetExists
            } finally {
                Files.deleteIfExists(temp)
            }
            Files.delete(file)
            WriteResult.Written
        }

    override fun display(file: Path): String = root.relativize(file).toString()

    /** Walks the project without following symlinks, skipping hidden directories (`.git`, `.gradle`) and `build` output. */
    override suspend fun kotlinFiles(): List<Path> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Path>()
        Files.walkFileTree(
            root,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = dir.fileName.toString()
                    val skipped = dir != root && (name.startsWith(".") || name == "build")
                    return if (skipped) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    val name = file.fileName.toString()
                    if (attrs.isRegularFile && (name.endsWith(".kt") || name.endsWith(".kts"))) found.add(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = FileVisitResult.CONTINUE
            },
        )
        found.sorted()
    }

    private fun readBytes(file: Path): ByteArray? = try {
        if (Files.isRegularFile(file)) Files.readAllBytes(file) else null
    } catch (_: NoSuchFileException) {
        null
    }

    private fun hash(bytes: ByteArray) =
        ContentHash(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
}
