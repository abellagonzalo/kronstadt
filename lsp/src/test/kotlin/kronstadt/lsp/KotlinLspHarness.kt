@file:OptIn(ExperimentalPathApi::class) // copyToRecursively, deleteRecursively

package kronstadt.lsp

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kronstadt.core.domain.Readiness
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * One contract test class's own copy of a fixture and its own kotlin-lsp on it, started before the class's tests and
 * shut down and deleted after them. The one place lsp contract tests build them; a class registers it on a
 * `@JvmField @RegisterExtension` field, under `@TestInstance(PER_CLASS)`, and supplies only its fixture and how it
 * prepares the copy. A class's own `@BeforeAll` runs after kotlin-lsp started, its `@AfterAll` before it is shut down.
 */
internal class KotlinLspHarness(
    /** The system property, set by `:lsp:contractTest`, that holds the fixture's path. */
    private val fixtureProperty: String = "kronstadt.fixture",
    /** Changes the copy of the fixture, the project, before kotlin-lsp starts on it. */
    private val prepare: (project: Path) -> Unit = {},
) : BeforeAllCallback, AfterAllCallback {
    /** The copy of the fixture kotlin-lsp runs on. */
    lateinit var project: Path
        private set

    /** The index directory kotlin-lsp was given; missing until kotlin-lsp creates it, as for a new worktree. */
    lateinit var indexDir: Path
        private set

    /** Kronstadt's adapter to that kotlin-lsp. */
    lateinit var lsp: LspCodeIntelligence
        private set

    override fun beforeAll(context: ExtensionContext) {
        val fixture = Path.of(requireNotNull(System.getProperty(fixtureProperty)) { "run via ./gradlew :lsp:contractTest" })
        project = Files.createTempDirectory("kronstadt-fixture-${context.requiredTestClass.simpleName}").toRealPath()
        fixture.copyToRecursively(project, followLinks = false, overwrite = true)
        prepare(project)
        val command = (System.getenv("KRONSTADT_LANGUAGE_SERVER") ?: "kotlin-lsp --stdio").trim().split(Regex("\\s+"))
        indexDir = Files.createTempDirectory("kronstadt-index").toRealPath().resolve("kotlin-server")
        lsp = runBlocking { LspCodeIntelligence.start(command, project, indexDir) }
    }

    override fun afterAll(context: ExtensionContext) {
        if (::lsp.isInitialized) lsp.close()
        if (::project.isInitialized) project.deleteRecursively()
        if (::indexDir.isInitialized) indexDir.parent.deleteRecursively()
    }

    /** Polls kotlin-lsp until Kronstadt reports it ready; fails if it isn't within [timeout]. */
    fun waitUntilReady(timeout: Duration = 5.minutes): Unit = runBlocking {
        withTimeout(timeout) {
            while (lsp.awaitReady(Duration.ZERO) != Readiness.Ready) delay(20.milliseconds)
        }
    }
}
