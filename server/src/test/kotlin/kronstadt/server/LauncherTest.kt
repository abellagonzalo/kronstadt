package kronstadt.server

import org.junit.jupiter.api.Tag
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The installed `bin/kronstadt` launcher picks its JDK: `KRONSTADT_JAVA_HOME`, else the JDK it was built with, else
 * `JAVA_HOME`, then `PATH`. Needs `installDist`; run with `./gradlew :server:launcherTest` (part of `check`).
 */
@Tag("launcher")
class LauncherTest {
    private val install: Path = Path.of(System.getProperty("kronstadt.install"))
    private val buildJavaHome: String = System.getProperty("kronstadt.buildJavaHome")
    private val tmp: Path = Files.createTempDirectory("kronstadt-launcher").toRealPath()

    /** A JDK whose `bin/java` only says it ran and fails, as an older JDK fails on Kronstadt's classes. */
    private val fakeJdk: Path = tmp.resolve("fake-jdk").also { home ->
        val java = home.resolve("bin").createDirectories().resolve("java")
        java.writeText("#!/bin/sh\necho \"fake java ran\" >&2\nexit 97\n")
        Files.setPosixFilePermissions(java, PosixFilePermissions.fromString("rwxr-xr-x"))
    }

    @AfterTest
    fun cleanup() {
        tmp.toFile().deleteRecursively()
    }

    @Test
    fun `JAVA_HOME and PATH pointing at another JDK don't stop the server starting on the build JDK`() {
        val run = launch(install.resolve("bin/kronstadt"), "JAVA_HOME" to fakeJdk.toString())

        assertEquals(2, run.exitCode, run.stderr)
        assertTrue(run.stderr.startsWith("unknown argument: --no-such-option\n\nUsage: kronstadt"), run.stderr)
    }

    @Test
    fun `KRONSTADT_JAVA_HOME wins over the build JDK`() {
        val run = launch(install.resolve("bin/kronstadt"), "KRONSTADT_JAVA_HOME" to fakeJdk.toString())

        assertEquals(97, run.exitCode, run.stderr)
        assertEquals("fake java ran\n", run.stderr)
    }

    @Test
    fun `a KRONSTADT_JAVA_HOME without bin-java fails naming it`() {
        val missing = tmp.resolve("no-jdk")
        val run = launch(install.resolve("bin/kronstadt"), "KRONSTADT_JAVA_HOME" to missing.toString())

        assertEquals(1, run.exitCode, run.stderr)
        assertEquals("\nERROR: KRONSTADT_JAVA_HOME is set to an invalid directory: $missing\n\n", run.stderr)
    }

    @Test
    fun `without the build JDK, the launcher falls back to JAVA_HOME`() {
        val run = launch(launcherWithoutBuildJdk(), "JAVA_HOME" to fakeJdk.toString())

        assertEquals(97, run.exitCode, run.stderr)
        assertEquals("fake java ran\n", run.stderr)
    }

    @Test
    fun `without the build JDK or JAVA_HOME, the launcher falls back to PATH`() {
        val run = launch(launcherWithoutBuildJdk())

        assertEquals(97, run.exitCode, run.stderr)
        assertEquals("fake java ran\n", run.stderr)
    }

    /** A copy of the installed distribution whose launcher records a build JDK that no longer exists. */
    private fun launcherWithoutBuildJdk(): Path {
        val copy = tmp.resolve("install")
        install.toFile().copyRecursively(copy.toFile())
        val launcher = copy.resolve("bin/kronstadt")
        val script = launcher.readText()
        assertTrue(buildJavaHome in script, "the launcher doesn't record the build JDK $buildJavaHome")
        launcher.writeText(script.replace(buildJavaHome, tmp.resolve("removed-jdk").toString()))
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"))
        return launcher
    }

    private class Run(val exitCode: Int, val stderr: String)

    /** Runs [launcher] with an unknown option, the fake JDK first on `PATH`, and of the JDK variables only [env]. */
    private fun launch(launcher: Path, vararg env: Pair<String, String>): Run {
        val stderr = tmp.resolve("stderr")
        val builder = ProcessBuilder(launcher.toString(), "--no-such-option")
            .redirectError(stderr.toFile())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        builder.environment().apply {
            remove("JAVA_HOME")
            remove("KRONSTADT_JAVA_HOME")
            put("PATH", "${fakeJdk.resolve("bin")}:/usr/bin:/bin")
            putAll(env)
        }
        val process = builder.start()
        assertTrue(process.waitFor(60, TimeUnit.SECONDS), "the launcher didn't exit")
        return Run(process.exitValue(), stderr.readText())
    }
}
