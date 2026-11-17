package kronstadt.workspace

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class IndexSeedsTest {
    private val tmp: Path = Files.createTempDirectory("kronstadt-test").toRealPath()
    private val directories = IndexDirectories(tmp.resolve("cache"))
    private val seeds = IndexSeeds(directories)

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `a worktree of a repo without a seed indexes cold`() {
        val main = gitRepo("repo")

        val index = seeds.prepare(main)

        assertEquals(Seeding.Cold("no Index Seed yet"), index.seeding)
        assertEquals(directories.forWorktree(main), index.dir)
        assertFalse(index.dir.exists(), "kotlin-lsp should create the index directory itself")
    }

    @Test
    fun `a new worktree starts from the seed its repo refreshed on a sibling's clean shutdown`() {
        val main = gitRepo("repo")
        val feature = gitWorktree(main, "feature")
        val mainIndex = seeds.prepare(main)
        fillWithIndexFiles(mainIndex.dir, "warm")

        assertEquals(SeedRefresh.Refreshed, seeds.refresh(mainIndex))
        val featureIndex = seeds.prepare(feature)

        assertEquals(Seeding.Seeded, featureIndex.seeding)
        assertEquals(directories.forWorktree(feature), featureIndex.dir)
        assertEquals("warm", featureIndex.dir.resolve("rocks/v241/000001.sst").readText())
    }

    @Test
    fun `outside a git repository there is no seed slot, so it indexes cold and never refreshes a seed`() {
        val plain = tmp.resolve("plain").createDirectories()

        val index = seeds.prepare(plain)
        fillWithIndexFiles(index.dir, "warm")

        assertEquals(Seeding.Cold("not a git repository"), index.seeding)
        assertEquals(SeedRefresh.Skipped("not a git repository"), seeds.refresh(index))
        assertFalse(directories.cacheRoot.resolve("seed").exists())
    }

    @Test
    fun `when the seed cannot be cloned copy-on-write, the worktree indexes cold with nothing half-copied left behind`() {
        val main = gitRepo("repo")
        val feature = gitWorktree(main, "feature")
        val mainIndex = seeds.prepare(main)
        fillWithIndexFiles(mainIndex.dir, "warm")
        seeds.refresh(mainIndex)
        val noCopyOnWrite = object : CopyOnWrite {
            override fun clone(source: Path, target: Path) {
                target.resolve("rocks").createDirectories()
                throw IOException("Operation not supported (errno 45)")
            }

            override fun swap(a: Path, b: Path) = error("not reached")
        }

        val featureIndex = IndexSeeds(directories, noCopyOnWrite).prepare(feature)

        assertEquals(Seeding.Cold("could not clone the Index Seed: Operation not supported (errno 45)"), featureIndex.seeding)
        assertEquals(listOf("worktree"), featureIndex.dir.parent.listDirectoryEntries().map { it.name })
    }

    @Test
    fun `each clean shutdown replaces the seed whole, leaving no temporary clones behind`() {
        val main = gitRepo("repo")
        val feature = gitWorktree(main, "feature")
        val mainIndex = seeds.prepare(main)
        fillWithIndexFiles(mainIndex.dir, "old")
        mainIndex.dir.resolve("rocks/v241/000000.sst").writeText("compacted away")
        seeds.refresh(mainIndex)
        mainIndex.dir.resolve("rocks/v241/000000.sst").deleteExisting()
        fillWithIndexFiles(mainIndex.dir, "new")

        assertEquals(SeedRefresh.Refreshed, seeds.refresh(mainIndex))
        val featureIndex = seeds.prepare(feature)

        assertEquals("new", featureIndex.dir.resolve("rocks/v241/000001.sst").readText())
        assertFalse(featureIndex.dir.resolve("rocks/v241/000000.sst").exists())
        val seedSlotOwner = directories.cacheRoot.resolve("seed").listDirectoryEntries().single()
        assertEquals(setOf("git-common-dir", "kotlin-server"), seedSlotOwner.listDirectoryEntries().map { it.name }.toSet())
    }

    @Test
    fun `seeding first deletes a half-made clone that a killed instance left behind`() {
        val main = gitRepo("repo")
        val feature = gitWorktree(main, "feature")
        val mainIndex = seeds.prepare(main)
        fillWithIndexFiles(mainIndex.dir, "warm")
        seeds.refresh(mainIndex)
        val leftover = directories.forWorktree(feature).resolveSibling(".kotlin-server-0b9e").createDirectories()
        leftover.resolve("000001.sst").writeText("half")

        val featureIndex = seeds.prepare(feature)

        assertEquals(Seeding.Seeded, featureIndex.seeding)
        assertEquals(setOf("worktree", "kotlin-server"), featureIndex.dir.parent.listDirectoryEntries().map { it.name }.toSet())
    }

    @Test
    fun `a worktree that already has an index keeps it, whatever the seed holds`() {
        val main = gitRepo("repo")
        val feature = gitWorktree(main, "feature")
        val mainIndex = seeds.prepare(main)
        fillWithIndexFiles(mainIndex.dir, "seed")
        seeds.refresh(mainIndex)
        fillWithIndexFiles(directories.forWorktree(feature), "own")

        val featureIndex = seeds.prepare(feature)

        assertEquals(Seeding.AlreadyIndexed, featureIndex.seeding)
        assertEquals("own", featureIndex.dir.resolve("rocks/v241/000001.sst").readText())
    }

    @Test
    fun `refreshing waits until the exiting kotlin-lsp has released its index lock`() {
        val main = gitRepo("repo")
        val mainIndex = seeds.prepare(main)
        fillWithIndexFiles(mainIndex.dir, "warm")
        holdLock(mainIndex.dir.resolve("rocks/v241/LOCK"), millis = 500)

        assertEquals(SeedRefresh.Refreshed, IndexSeeds(directories, lockWait = 10.seconds).refresh(mainIndex))
    }

    @Test
    fun `an index still locked after the wait never becomes the seed`() {
        val main = gitRepo("repo")
        val feature = gitWorktree(main, "feature")
        val mainIndex = seeds.prepare(main)
        fillWithIndexFiles(mainIndex.dir, "warm")
        val lock = mainIndex.dir.resolve("rocks/v241/LOCK")
        val holder = holdLock(lock, millis = 60_000)
        try {
            val refresh = IndexSeeds(directories, lockWait = 300.milliseconds).refresh(mainIndex)

            assertEquals(SeedRefresh.Skipped("$lock is still locked"), refresh)
            assertEquals(Seeding.Cold("no Index Seed yet"), seeds.prepare(feature).seeding)
        } finally {
            holder.destroyForcibly().waitFor()
        }
    }

    /** Starts another process that holds a POSIX lock on [file], as RocksDB does, for [millis]; returns once held. */
    private fun holdLock(file: Path, millis: Long): Process {
        val source = tmp.resolve("locker/Locker.java")
        source.parent.createDirectories()
        source.writeText(
            """
            import java.nio.channels.FileChannel;
            import java.nio.file.*;
            public class Locker {
                public static void main(String[] args) throws Exception {
                    try (var channel = FileChannel.open(Path.of(args[0]), StandardOpenOption.WRITE); var lock = channel.lock()) {
                        System.out.println("locked");
                        System.out.flush();
                        Thread.sleep(Long.parseLong(args[1]));
                    }
                }
            }
            """.trimIndent(),
        )
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(java, source.toString(), file.toString(), millis.toString())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        check(process.inputReader().readLine() == "locked") { "the lock holder did not start" }
        return process
    }

    private fun fillWithIndexFiles(dir: Path, content: String) {
        dir.resolve("rocks/v241").createDirectories()
        dir.resolve("rocks/v241/000001.sst").writeText(content)
        dir.resolve("rocks/v241/LOCK").writeText("")
    }

    private fun gitWorktree(repo: Path, name: String): Path {
        val worktree = tmp.resolve(name)
        git(repo, "worktree", "add", "--quiet", "-b", name, worktree.toString())
        return worktree.toRealPath()
    }

    private fun gitRepo(name: String): Path {
        val repo = tmp.resolve(name).createDirectories()
        git(repo, "init", "--quiet")
        repo.resolve("README").writeText("seed\n")
        git(repo, "add", ".")
        git(repo, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "init")
        return repo.toRealPath()
    }

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).inheritIO().start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed" }
    }
}
