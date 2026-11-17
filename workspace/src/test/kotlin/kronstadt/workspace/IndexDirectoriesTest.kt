package kronstadt.workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IndexDirectoriesTest {
    private val tmp: Path = Files.createTempDirectory("kronstadt-test").toRealPath()
    private val cache = tmp.resolve("cache")
    private val worktreeA = tmp.resolve("repo/main").createDirectories()
    private val worktreeB = tmp.resolve("repo/feature").createDirectories()
    private val indexes = IndexDirectories(cache)

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `each worktree gets its own index directory under the cache, stable across starts`() {
        val a = indexes.forWorktree(worktreeA)
        val b = indexes.forWorktree(worktreeB)

        assertTrue(a.startsWith(cache.resolve("index")), "$a is not under the cache's index directory")
        assertNotEquals(a, b)
        assertEquals(a, IndexDirectories(cache).forWorktree(worktreeA))
    }

    @Test
    fun `the same worktree reached through a different spelling of its path gets the same index directory`() {
        val link = Files.createSymbolicLink(tmp.resolve("link"), worktreeA)

        assertEquals(indexes.forWorktree(worktreeA), indexes.forWorktree(link))
        assertEquals(indexes.forWorktree(worktreeA), indexes.forWorktree(worktreeA.resolve("../main")))
    }

    @Test
    fun `the index directory records which worktree it belongs to`() {
        val index = indexes.forWorktree(worktreeA)

        assertEquals(worktreeA.toString(), index.parent.resolve(IndexDirectories.WORKTREE_FILE).readText().trim())
    }

    @Test
    fun `pruning deletes the index of a deleted worktree and keeps the index of a live one intact`() {
        val live = indexes.forWorktree(worktreeA).also(::fillWithIndexFiles)
        val dead = indexes.forWorktree(worktreeB).also(::fillWithIndexFiles)
        worktreeB.toFile().deleteRecursively()

        indexes.prune()

        assertTrue(live.resolve("db/000001.sst").exists(), "the live worktree's index was touched")
        assertTrue(live.parent.resolve(IndexDirectories.WORKTREE_FILE).exists())
        assertFalse(dead.parent.exists(), "the deleted worktree's index is still there")
    }

    @Test
    fun `pruning deletes the seed slot of a deleted clone and keeps the seed slot of a live one`() {
        val liveGitDir = tmp.resolve("repo/.git").createDirectories()
        val deadGitDir = tmp.resolve("gone/.git").createDirectories()
        val live = indexes.seedSlot(liveGitDir).also(::fillWithIndexFiles)
        val dead = indexes.seedSlot(deadGitDir).also(::fillWithIndexFiles)
        tmp.resolve("gone").toFile().deleteRecursively()

        indexes.prune()

        assertTrue(live.resolve("db/000001.sst").exists(), "the live clone's seed was touched")
        assertFalse(dead.parent.exists(), "the deleted clone's seed slot is still there")
    }

    @Test
    fun `pruning leaves alone a directory that records no owner, as one being set up right now`() {
        val unrecorded = cache.resolve("index/0123456789abcdef").createDirectories()

        indexes.prune()

        assertTrue(unrecorded.exists())
    }

    private fun fillWithIndexFiles(index: Path) {
        index.resolve("db").createDirectories().resolve("000001.sst").writeText("rocks")
    }
}
