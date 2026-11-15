package kronstadt.workspace

import kotlinx.coroutines.runBlocking
import kronstadt.core.domain.TextDocument
import kronstadt.core.port.WriteResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.deleteExisting
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class LocalFileStoreTest {
    private val tmp: Path = Files.createTempDirectory("kronstadt-test").toRealPath()
    private val project = tmp.resolve("project").createDirectories()
    private val outside = tmp.resolve("outside").createDirectories()
    private val store = LocalFileStore(project)

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `resolves relative and absolute paths inside the project`() {
        val file = project.resolve("src/A.kt")
        assertEquals(file, store.resolve("src/A.kt"))
        assertEquals(file, store.resolve(file.toString()))
        assertEquals("src/A.kt", store.display(file))
    }

    @Test
    fun `rejects paths that escape the project`() {
        assertNull(store.resolve("../outside/B.kt"))
        assertNull(store.resolve(outside.resolve("B.kt").toString()))
    }

    @Test
    fun `rejects symlinks pointing outside the project`() {
        outside.resolve("secret.kt").writeText("val x = 1")
        project.resolve("link.kt").createSymbolicLinkPointingTo(outside.resolve("secret.kt"))
        assertNull(store.resolve("link.kt"))
    }

    @Test
    fun `reads existing files and returns null for missing ones`() = runBlocking {
        project.resolve("A.kt").writeText("class A")
        assertEquals("class A", store.read(project.resolve("A.kt"))?.text)
        assertNull(store.read(project.resolve("Missing.kt")))
    }

    @Test
    fun `a hashed read's hash changes exactly when the file's bytes change`() = runBlocking {
        val file = project.resolve("A.kt").apply { writeText("class A") }
        val first = assertNotNull(store.readHashed(file))
        assertEquals("class A", first.document.text)
        assertEquals(first.hash, store.readHashed(file)?.hash)
        file.writeText("class A ")
        assertNotEquals(first.hash, store.readHashed(file)?.hash)
        assertNull(store.readHashed(project.resolve("Missing.kt")))
    }

    @Test
    fun `writes the new content when the file is unchanged since the read, keeping its permissions`() = runBlocking {
        val file = project.resolve("A.kt").apply { writeText("class A\r\n") }
        val permissions = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(file, permissions)
        val read = assertNotNull(store.readHashed(file))

        assertEquals(WriteResult.Written, store.writeIfUnchanged(file, read.hash, TextDocument("class B\r\n")))

        assertEquals("class B\r\n", file.readText())
        assertEquals(permissions, Files.getPosixFilePermissions(file))
        assertEquals(listOf("A.kt"), project.listDirectoryEntries().map { it.name }, "no temp file is left behind")
    }

    @Test
    fun `refuses to write when the file changed or vanished since the read`() = runBlocking {
        val file = project.resolve("A.kt").apply { writeText("class A") }
        val read = assertNotNull(store.readHashed(file))
        file.writeText("class Theirs")

        assertEquals(WriteResult.ChangedOnDisk, store.writeIfUnchanged(file, read.hash, TextDocument("class Mine")))
        assertEquals("class Theirs", file.readText())

        file.deleteExisting()
        assertEquals(WriteResult.ChangedOnDisk, store.writeIfUnchanged(file, read.hash, TextDocument("class Mine")))
        assertEquals(emptyList(), project.listDirectoryEntries())
    }

    @Test
    fun `lists the project's Kotlin files, skipping hidden directories and build output`() = runBlocking {
        listOf("build.gradle.kts", "src/main/kotlin/A.kt", "app/src/test/kotlin/BTest.kt", "README.md", "src/main/java/C.java",
            "build/generated/D.kt", "app/build/E.kt", ".gradle/F.kts", ".git/G.kt").forEach { path ->
            project.resolve(path).also { it.parent.createDirectories() }.writeText("")
        }
        outside.resolve("H.kt").writeText("")
        project.resolve("src/linked").createSymbolicLinkPointingTo(outside)

        assertEquals(
            listOf("app/src/test/kotlin/BTest.kt", "build.gradle.kts", "src/main/kotlin/A.kt"),
            store.kotlinFiles().map(store::display),
        )
    }
}
