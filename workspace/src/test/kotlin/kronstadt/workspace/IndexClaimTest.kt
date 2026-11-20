package kronstadt.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IndexClaimTest {
    private val tmp: Path = Files.createTempDirectory("kronstadt-test").toRealPath()
    private val directories = IndexDirectories(tmp.resolve("cache"))
    private val worktreeA = tmp.resolve("a").createDirectories()
    private val worktreeB = tmp.resolve("b").createDirectories()
    private val claims = mutableListOf<IndexClaim.Held>()

    @AfterTest
    fun cleanup() {
        claims.forEach { it.release() }
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `while a worktree's index is claimed, a second claim on it reports the holder's pid, and succeeds once it is released`() {
        val first = held(directories.claim(worktreeA))

        assertEquals(IndexClaim.HeldByAnother(ProcessHandle.current().pid()), IndexDirectories(directories.cacheRoot).claim(worktreeA))

        first.release()
        held(directories.claim(worktreeA))
    }

    @Test
    fun `a claim on another worktree succeeds while the first is held`() {
        held(directories.claim(worktreeA))

        held(directories.claim(worktreeB))
    }

    @Test
    fun `an index claimed by another process is reported held by that process's pid, and claimable once it exits`() {
        val holder = claimInAnotherProcess(worktreeA)

        assertEquals(IndexClaim.HeldByAnother(holder.pid()), directories.claim(worktreeA))

        holder.outputStream.close()
        assertTrue(holder.waitFor(30, TimeUnit.SECONDS), "the holder did not exit")
        held(directories.claim(worktreeA))
    }

    @Test
    fun `the lock file sits next to the worktree record, outside kotlin-lsp's index directory`() {
        val index = directories.forWorktree(worktreeA)

        val lockFile = held(directories.claim(worktreeA)).lockFile

        assertTrue(lockFile.exists())
        assertEquals(index.parent, lockFile.parent)
        assertFalse(lockFile.startsWith(index), "$lockFile is inside $index")
    }

    @Test
    fun `refreshing the Index Seed from a claimed worktree's index, and seeding another from it, never copies the lock file`() {
        val main = gitRepo("repo")
        val feature = gitWorktree(main, "feature")
        val seeds = IndexSeeds(directories)
        held(directories.claim(main))
        val mainIndex = seeds.prepare(main)
        mainIndex.dir.resolve("rocks/v241").createDirectories()
        mainIndex.dir.resolve("rocks/v241/000001.sst").writeText("warm")

        assertEquals(SeedRefresh.Refreshed, seeds.refresh(mainIndex))
        held(directories.claim(feature))
        val featureIndex = seeds.prepare(feature)

        assertEquals(Seeding.Seeded, featureIndex.seeding)
        for (dir in listOf(directories.seedSlot(main.resolve(".git")), featureIndex.dir)) {
            val copied = Files.walk(dir).use { paths -> paths.filter { it.name == "lock" }.toList() }
            assertEquals(emptyList(), copied, "a lock file was copied into $dir")
        }
    }

    @Test
    fun `pruning leaves a claimed worktree's index directory alone, without waiting on its lock`() {
        val index = directories.forWorktree(worktreeA)
        index.resolve("rocks").createDirectories()
        val claim = held(directories.claim(worktreeA))

        directories.prune()

        assertTrue(index.resolve("rocks").exists(), "the claimed worktree's index was pruned")
        assertTrue(claim.lockFile.exists())
    }

    private fun held(claim: IndexClaim): IndexClaim.Held = assertIs<IndexClaim.Held>(claim).also { claims += it }

    /** Starts a JVM that claims [worktree]'s index and holds it until its stdin closes; returns once it holds it. */
    private fun claimInAnotherProcess(worktree: Path): Process {
        val java = ProcessHandle.current().info().command().orElseThrow()
        val process = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"), ClaimHolder::class.java.name,
            directories.cacheRoot.toString(), worktree.toString(),
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
        check(process.inputReader().readLine() == "held") { "the other process did not claim the index" }
        return process
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

/** Claims a worktree's index in its own process: `<cacheRoot> <worktree>`. Prints "held", then holds it until stdin closes. */
object ClaimHolder {
    @JvmStatic
    fun main(args: Array<String>) {
        val claim = IndexDirectories(Path.of(args[0])).claim(Path.of(args[1]))
        println(if (claim is IndexClaim.Held) "held" else "not held: $claim")
        System.out.flush()
        System.`in`.readAllBytes()
    }
}
