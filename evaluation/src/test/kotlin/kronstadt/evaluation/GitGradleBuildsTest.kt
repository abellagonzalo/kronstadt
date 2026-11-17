package kronstadt.evaluation

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readLines
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Seam B: the real adapter on a throwaway git repo. Its `gradlew` is a stub that logs the tasks it was asked to run and
 * fails, like the Kotlin compiler, on any `.kt` file containing `BROKEN`, so no real Gradle runs.
 */
class GitGradleBuildsTest {
    private val repo: Path = Files.createTempDirectory("repo").toRealPath()
    private val log: Path = Files.createTempFile("gradlew", ".log")
    private val builds = GitGradleBuilds()

    init {
        git("init", "-q", "-b", "main")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "Test")
        write("settings.gradle.kts", "include(\"app\")")
        write("build.gradle.kts", "")
        write("app/build.gradle.kts", "")
        write("app/src/main/kotlin/A.kt", "class A")
        write("app/src/test/kotlin/ATest.kt", "class ATest")
        write("src/main/kotlin/Root.kt", "class Root")
        write("README.md", "hi")
        write(
            "gradlew",
            """
            |#!/bin/bash
            |echo "${'$'}*" >> '$log'
            |status=0
            |for f in ${'$'}(grep -rl BROKEN --include='*.kt' . | sort); do
            |  echo "e: file://${'$'}(pwd -P)/${'$'}{f#./}:1:7 Unresolved reference 'BROKEN'." >&2
            |  status=1
            |done
            |if grep -rq NO_DEPS --include='*.kt' .; then echo "Could not resolve all dependencies for configuration ':app:compileClasspath'." >&2; status=1; fi
            |exit ${'$'}status
            """.trimMargin(),
        )
        repo.resolve("gradlew").toFile().setExecutable(true)
        commit("initial")
    }

    @Test
    fun `a commit that breaks a file its parent compiled is reported with the broken files`() {
        write("app/src/main/kotlin/A.kt", "class A { val x = BROKEN }")
        val broken = commit("break A")

        val breakage = builds.breakage(repo, broken)

        assertEquals(Breakage.Breaks(setOf(repo.resolve("app/src/main/kotlin/A.kt"))), breakage)
        assertEquals(listOf(":app:compileKotlin :app:compileTestKotlin --quiet"), log.readLines().take(1))
    }

    @Test
    fun `compiles the modules the commit touched, the root project included`() {
        write("app/src/test/kotlin/ATest.kt", "class ATest { }")
        write("src/main/kotlin/Root.kt", "class Root { }")

        assertEquals(Breakage.Compiles, builds.breakage(repo, commit("touch both")))
        assertEquals(listOf(":compileKotlin :compileTestKotlin :app:compileKotlin :app:compileTestKotlin --quiet"), log.readLines())
    }

    @Test
    fun `a commit that leaves an already broken parent broken is not its breakage`() {
        write("app/src/main/kotlin/A.kt", "class A { val x = BROKEN }")
        commit("break A")
        write("src/main/kotlin/Root.kt", "class Root { }")

        assertEquals(Breakage.ParentBroken, builds.breakage(repo, commit("touch Root")))
    }

    @Test
    fun `a build failing without compiler errors is unavailable, not a breakage`() {
        write("app/src/main/kotlin/A.kt", "class A // NO_DEPS")

        val breakage = builds.breakage(repo, commit("no deps"))

        assertIs<Breakage.Unavailable>(breakage)
        assertEquals(true, "Could not resolve all dependencies" in breakage.reason, breakage.reason)
    }

    @Test
    fun `a commit touching no kt file is not compiled`() {
        write("README.md", "changed")
        write("build.gradle.kts", "// changed")

        assertEquals(Breakage.NotKotlin, builds.breakage(repo, commit("docs")))
        assertEquals(emptyList(), log.readLines())
    }

    @Test
    fun `missing commits and repos are unavailable, and no worktree is left behind`() {
        write("app/src/main/kotlin/A.kt", "class A { val x = BROKEN }")
        builds.breakage(repo, commit("break A"))

        assertIs<Breakage.Unavailable>(builds.breakage(repo, "0123456789abcdef"))
        assertIs<Breakage.Unavailable>(builds.breakage(repo.resolve("gone"), "0123456"))
        assertEquals(1, git("worktree", "list").lines().count { it.isNotBlank() })
    }

    private fun write(path: String, text: String) {
        repo.resolve(path).also { it.parent.createDirectories() }.writeText(text)
    }

    private fun commit(message: String): String {
        git("add", "-A")
        git("commit", "-q", "-m", message)
        return git("rev-parse", "--short", "HEAD").trim()
    }

    private fun git(vararg args: String): String {
        val process = ProcessBuilder("git", *args).directory(repo.toFile()).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) { "git ${args.joinToString(" ")}: $out" }
        return out
    }
}
