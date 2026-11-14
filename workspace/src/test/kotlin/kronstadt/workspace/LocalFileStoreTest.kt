package kronstadt.workspace

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
