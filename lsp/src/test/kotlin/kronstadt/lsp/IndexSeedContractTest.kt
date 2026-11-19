@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.runBlocking
import kronstadt.core.domain.Readiness
import kronstadt.workspace.IndexDirectories
import kronstadt.workspace.IndexSeeds
import kronstadt.workspace.SeedRefresh
import kronstadt.workspace.Seeding
import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteExisting
import kotlin.io.path.deleteRecursively
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Seam 2 acceptance test for the Index Seed: a worktree indexes cold and shuts down cleanly, refreshing its repo's
 * seed; a sibling worktree that diverged from it starts from a clone of that seed and answers for its own tree.
 */
@Tag("contract")
class IndexSeedContractTest {
    private val tmp: Path = Files.createTempDirectory("kronstadt-seed").toRealPath()
    private val seeds = IndexSeeds(IndexDirectories(tmp.resolve("cache")))
    private val command = (System.getenv("KRONSTADT_LANGUAGE_SERVER") ?: "kotlin-lsp --stdio").trim().split(Regex("\\s+"))

    @AfterTest
    fun cleanup() {
        tmp.deleteRecursively()
    }

    @Test
    fun `a new worktree started from a cloned seed becomes ready and answers for its own tree`() = runBlocking {
        val main = tmp.resolve("main")
        val fixture = Path.of(requireNotNull(System.getProperty("kronstadt.fixture")) { "run via ./gradlew :lsp:contractTest" })
        fixture.copyToRecursively(main, followLinks = false, overwrite = false)
        main.resolve("src/main/kotlin/fixture/Leaving.kt").writeText("package fixture\n\nclass LeavingInFeature\n")
        git(main, "init", "--quiet")
        git(main, "add", ".")
        git(main, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "--quiet", "-m", "fixture")
        val feature = tmp.resolve("feature")
        git(main, "worktree", "add", "--quiet", "-b", "feature", feature.toString())
        feature.resolve("src/main/kotlin/fixture/Leaving.kt").deleteExisting()
        feature.resolve("src/main/kotlin/fixture/Arrived.kt").writeText("package fixture\n\nclass ArrivedInFeature\n")

        val mainIndex = seeds.prepare(main)
        assertEquals(Seeding.Cold("no Index Seed yet"), mainIndex.seeding)
        val mainLsp = LspCodeIntelligence.start(command, main, mainIndex.dir)
        assertEquals(Readiness.Ready, mainLsp.awaitReady(5.minutes))
        assertEquals(listOf("LeavingInFeature"), mainLsp.workspaceSymbols("LeavingInFeature").symbols.map { it.name })
        assertTrue(mainLsp.shutdown(), "kotlin-lsp did not exit cleanly")
        assertEquals(SeedRefresh.Refreshed, seeds.refresh(mainIndex))

        val featureIndex = seeds.prepare(feature)
        assertEquals(Seeding.Seeded, featureIndex.seeding)
        val start = TimeSource.Monotonic.markNow()
        val featureLsp = LspCodeIntelligence.start(command, feature, featureIndex.dir)
        try {
            assertEquals(Readiness.Ready, featureLsp.awaitReady(5.minutes))
            System.err.println("seeded worktree ready in ${start.elapsedNow()}")

            assertEquals(emptyList(), featureLsp.workspaceSymbols("LeavingInFeature").symbols.map { it.name })
            assertEquals(listOf("ArrivedInFeature"), featureLsp.workspaceSymbols("ArrivedInFeature").symbols.map { it.name })
            val found = listOf("Shape", "Calculator", "Arrived").flatMap { featureLsp.workspaceSymbols(it).symbols }
            assertTrue(found.isNotEmpty())
            val outside = found.map { it.file }.filterNot { it.startsWith(feature) }
            assertEquals(emptyList(), outside, "results outside $feature")
        } finally {
            featureLsp.close()
        }
    }

    private fun git(dir: Path, vararg args: String) {
        val process = ProcessBuilder(listOf("git", "-C", dir.toString()) + args).inheritIO().start()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")} failed" }
    }
}
