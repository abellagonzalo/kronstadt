package kronstadt.evaluation

import java.nio.file.Path
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AnalyserTest {
    private val transcripts = FixtureTranscripts()
    private val builds = FakeBuilds()

    private fun analyse(filter: SampleFilter = SampleFilter()) = Analyser(builds).analyse(transcripts.projects, filter)

    @Test
    fun `the sample is the sessions touching a kt file inside the included repos, minus the excluded ones, from the start date on`() {
        transcripts.session("read", "/work/service-a") { read("/work/service-a/src/A.kt") }
        transcripts.session("no-kotlin", "/work/service-a") { read("/work/service-a/README.md"); read("/work/service-a/build.gradle.kts") }
        transcripts.session("bash-only", "/work/service-b") { bash("git diff -- src/B.kt") }
        transcripts.session("kronstadt-only", "/work/service-b/sub") { kronstadt("kt_find_symbol", "query" to "B") }
        transcripts.session("own-repo", "/work/kronstadt/main") { read("/work/kronstadt/main/core/X.kt") }
        transcripts.session("elsewhere", "/other/thing") { read("/other/thing/X.kt") }
        transcripts.session("too-early", "/work/service-a", start = "2026-11-01T23:59:59Z") { read("/work/service-a/src/A.kt") }
        transcripts.session("in-subagent", "/work/service-a") { subagent("a1") { grep("pattern" to "x", "glob" to "*.kt") } }

        val report = analyse(
            SampleFilter(
                include = listOf(Path.of("/work")),
                exclude = listOf(Path.of("/work/kronstadt")),
                since = Instant.parse("2026-11-02T00:00:00Z"),
            ),
        )

        assertEquals(setOf("read", "bash-only", "kronstadt-only", "in-subagent"), report.sessions.map { it.sessionId }.toSet())
    }

    @Test
    fun `Bash writes to existing kt files are circumventions, Bash creating a new kt file is not`() {
        transcripts.session("s", "/repo") {
            read("/repo/src/Seen.kt")
            bash("sed -i '' 's/a/b/' src/A.kt") // in-place edits need an existing file
            bash("cd /repo && perl -pi -e 's/x/y/' /repo/src/B.kt")
            bash("cat > src/New.kt <<'EOF'\nclass New\nEOF") // first mention: a new file
            bash("cat > src/Seen.kt <<'EOF'\nclass Seen // echo x > src/Body.kt is heredoc text\nEOF")
            bash("echo 'fun f() = 1' >> src/New.kt") // exists now: the session created it
            bash("generate | tee src/Seen.kt > /dev/null")
            bash("cp /tmp/Template.kt src/A.kt")
            bash("sed -n '1,20p' src/A.kt && grep -n 'x -> y' src/A.kt > /tmp/out.txt") // reads only
            bash("sed -i '' 's/a/b/' /tmp/Outside.kt") // outside the project
        }

        val circumventions = analyse().sessions.single().circumventions

        assertEquals(
            listOf(
                "/repo/src/A.kt" to "sed -i '' 's/a/b/' src/A.kt",
                "/repo/src/B.kt" to "perl -pi -e 's/x/y/' /repo/src/B.kt",
                "/repo/src/Seen.kt" to "cat > src/Seen.kt <<'EOF'",
                "/repo/src/New.kt" to "echo 'fun f() = 1' >> src/New.kt",
                "/repo/src/Seen.kt" to "tee src/Seen.kt > /dev/null",
                "/repo/src/A.kt" to "cp /tmp/Template.kt src/A.kt",
            ),
            circumventions.map { it.file.toString() to it.how },
        )
    }

    @Test
    fun `paths in a Bash command resolve against the directory a cd in the same command moved to`() {
        transcripts.session("s", "/repo") {
            bash("cd booking/src && sed -i '' 's/a/b/' main/A.kt; cd /repo/other && sed -i '' 's/a/b/' B.kt")
        }

        assertEquals(
            listOf("/repo/booking/src/main/A.kt", "/repo/other/B.kt"),
            analyse().sessions.single().circumventions.map { it.file.toString() },
        )
    }

    @Test
    fun `Write recreating a kt file the agent deleted is a circumvention, Write creating a new one is not`() {
        transcripts.session("s", "/repo") {
            read("/repo/src/A.kt")
            bash("rm src/A.kt src/Gone.kt")
            write("/repo/src/A.kt")
            bash("git rm -q /repo/src/B.kt")
            subagent("a1") { write("/repo/src/B.kt") }
            write("/repo/src/Fresh.kt")
        }

        val report = analyse()

        assertEquals(
            listOf("/repo/src/A.kt" to "Write after rm", "/repo/src/B.kt" to "Write after rm"),
            report.sessions.single().circumventions.map { it.file.toString() to it.how },
        )
        assertEquals(2, report.total.circumventions)
    }

    @Test
    fun `a corrupted file is one a Write-back touched that breaks compilation in a session commit whose parent compiled`() {
        transcripts.session("s", "/repo") {
            ktEdit("A/f", "Edited src/A.kt\n  A/f  // L3-5")
            call("mcp__kronstadt__kt_edit", FixtureTranscripts.obj("symbol" to "C"), "Edited src/C.kt", cwd = "/repo/sub") // after a cd
            ktEdit("B/g", "Edit rejected: 1 new error; nothing written.\n  src/B.kt:L4 in g: Unresolved reference 'x'.", isError = true)
            bash("git add -A && git commit -m 'first'", "[main 1a2b3c4] first\n 2 files changed, 3 insertions(+)")
            bash("git commit -am second", "[main 5d6e7f8] second\n 1 file changed")
            bash("git commit -m third", "[detached HEAD 9a9a9a9] third")
            bash("git commit -m nothing", "nothing to commit, working tree clean", isError = true)
            bash("cd ../lib && git commit -m 'other repo'", "[main 2c2c2c2] other repo")
            bash("git commit -am x && git push", "[main 3d3d3d3] x\nerror: failed to push some refs", isError = true)
            bash("GIT_EDITOR=true git cherry-pick --continue && git log --oneline -1", "[main 4e4e4e4] picked\n4e4e4e4 picked")
            subagent("a1") {
                bash("git commit -m 'in a worktree'", "[feature 0b0b0b0] in a worktree", cwd = "/repo/.claude/worktrees/a1")
            }
        }
        builds.on("/repo", "1a2b3c4", Breakage.Breaks(setOf(Path.of("/repo/src/A.kt"), Path.of("/repo/src/C.kt"))))
        builds.on("/repo", "5d6e7f8", Breakage.Breaks(setOf(Path.of("/repo/src/B.kt")))) // B was never written back
        builds.on("/repo", "9a9a9a9", Breakage.ParentBroken)
        builds.on("/repo/.claude/worktrees/a1", "0b0b0b0", Breakage.Unavailable("repo is gone"))

        val report = analyse()

        val session = report.sessions.single()
        assertEquals(listOf(Corruption("1a2b3c4", Path.of("/repo/src/A.kt")), Corruption("1a2b3c4", Path.of("/repo/src/C.kt"))), session.corruptions)
        assertEquals(listOf(UncheckedCommit("0b0b0b0", "repo is gone")), session.uncheckedCommits)
        assertEquals(
            listOf(
                "/repo" to "1a2b3c4",
                "/repo" to "5d6e7f8",
                "/repo" to "9a9a9a9",
                "/lib" to "2c2c2c2",
                "/repo" to "3d3d3d3",
                "/repo" to "4e4e4e4",
                "/repo/.claude/worktrees/a1" to "0b0b0b0",
            ),
            builds.checked.map { it.first.toString() to it.second },
        )
        assertEquals(2, report.total.corruptedFiles)
    }

    @Test
    fun `informational - Bash grep, cat and sed -n on kt files are counted by command`() {
        transcripts.session("s", "/repo") {
            bash("grep -rn 'foo' src --include=*.kt")
            bash("rg -t kotlin foo")
            bash("cat src/A.kt | head -5")
            bash("sed -n '1,40p' src/A.kt; echo ---; cat README.md")
            bash("grep foo README.md")
            bash("sed -i '' s/a/b/ src/A.kt") // a write, not a read
        }

        val session = analyse().sessions.single()

        assertEquals(mapOf("grep" to 2, "cat" to 1, "sed -n" to 1), session.bashKotlinReads)
    }

    @Test
    fun `informational - Validation rejection rate over the edits that reached Validation, and readiness timeouts`() {
        val timeout = "kotlin-lsp still indexing (phase indexing, 90 s elapsed); nothing returned/written, retry"
        transcripts.session("s1", "/repo") {
            ktEdit("A/f", "Edited src/A.kt")
            ktEdit("A/f", "Edit rejected: 2 new errors; nothing written.\n  src/A.kt:L3 in f: Unresolved reference 'x'.", isError = true)
            ktEdit("A/f", "Edit rejected: 1 new error; nothing written.\n  src/A.kt:L3 in f: Type mismatch.", isError = true)
            ktEdit("A/f", "Edited src/A.kt")
            kronstadt("kt_find_symbol", "query" to "A", result = timeout, isError = true)
        }
        transcripts.session("s2", "/repo") {
            ktEdit("B/g", timeout, isError = true) // never reached Validation
            ktEdit("B/g", "Operation 0: no Symbol 'B/g' in src/B.kt. Nothing written.", isError = true)
            kronstadt("kt_read_symbol", "symbol" to "B")
        }

        val report = analyse()

        assertEquals(listOf(Triple(4, 2, 1), Triple(0, 0, 1)), report.sessions.map { Triple(it.edits, it.rejectedEdits, it.readinessTimeouts) })
        assertEquals(0.5, report.total.rejectionRate)
        assertEquals(2, report.total.readinessTimeouts)
    }

    @Test
    fun `informational - hook denials of built-in edits and what the same agent did next`() {
        val denied = "Built-in edits of existing .kt files are disabled here. Use kt_edit (replace/insert/delete/edit_in by Symbol Path)."
        transcripts.session("s", "/repo") {
            edit("/repo/src/A.kt", denied, isError = true)
            ktEdit("A/f", "Edited src/A.kt")
            call("Write", FixtureTranscripts.obj("file_path" to "/repo/src/A.kt", "content" to "x"), denied, isError = true)
            bash("sed -i '' 's/a/b/' src/A.kt")
            subagent("a1") {
                edit("/repo/src/B.kt", denied, isError = true)
                kronstadt("kt_read_symbol", "symbol" to "B")
            }
            edit("/repo/src/C.kt", "String to replace not found in file.", isError = true) // a failure, not a denial
            read("/repo/src/C.kt")
            edit("/repo/src/D.kt", denied, isError = true)
        }

        val report = analyse()

        val expected = mapOf("kt_edit" to 1, "circumvention (Bash)" to 1, "kt_read_symbol" to 1, "(nothing)" to 1)
        assertEquals(expected, report.sessions.single().hookDenials)
        assertEquals(expected, report.total.hookDenials)
    }

    @Test
    fun `the bar is 0 corrupted files, 0 circumventions and at least 80 percent of Kotlin reads and searches through Kronstadt`() {
        transcripts.session("good", "/repo") {
            repeat(4) { kronstadt("kt_read_symbol", "symbol" to "A") }
            read("/repo/src/A.kt")
        }
        assertEquals(true, analyse().passes)

        transcripts.session("circumventing", "/repo") { bash("sed -i '' s/a/b/ src/A.kt") }
        assertEquals(false, analyse().passes)
    }

    @Test
    fun `a commit that could not be compiled leaves the corrupted-files bar unmet`() {
        transcripts.session("s", "/repo") {
            kronstadt("kt_read_symbol", "symbol" to "A")
            bash("git commit -am x", "[main 1a2b3c4] x")
        }
        builds.on("/repo", "1a2b3c4", Breakage.Unavailable("--no-compile"))

        val report = analyse()

        assertEquals(0, report.total.corruptedFiles)
        assertEquals(false, report.passes)
    }

    @Test
    fun `the sample is complete at 30 sessions spanning two weeks, whichever comes later`() {
        fun day(n: Int) = "2026-11-%02dT09:00:00Z".format(n)
        repeat(29) { transcripts.session("s$it", "/repo", start = day(1 + it % 15)) { kronstadt("kt_read_symbol", "symbol" to "A") } }
        assertEquals(false, analyse().sampleComplete) // two weeks, 29 sessions

        transcripts.session("s29", "/repo", start = day(2)) { kronstadt("kt_read_symbol", "symbol" to "A") }
        assertEquals(true, analyse().sampleComplete)

        val short = FixtureTranscripts()
        repeat(40) { short.session("s$it", "/repo", start = day(1 + it % 10)) { kronstadt("kt_read_symbol", "symbol" to "A") } }
        assertEquals(false, Analyser(builds).analyse(short.projects, SampleFilter()).sampleComplete) // 40 sessions, 9 days
    }

    @Test
    fun `the report shows each session, then the totals against the bar`() {
        transcripts.session("s1", "/repo", start = "2026-11-10T09:00:00Z") {
            kronstadt("kt_read_symbol", "symbol" to "A")
            read("/repo/src/A.kt")
            edit("/repo/src/A.kt", "Use kt_edit instead.", isError = true)
            bash("sed -i '' s/a/b/ src/A.kt")
            ktEdit("A/f", "Edited src/A.kt")
            bash("git commit -am x", "[main 1a2b3c4] x")
        }
        builds.on("/repo", "1a2b3c4", Breakage.Breaks(setOf(Path.of("/repo/src/A.kt"))))

        val text = analyse().render()

        assertEquals(
            """
            |Session s1  /repo  2026-11-10T09:00:00Z
            |  reads/searches: 1 Kronstadt, 1 built-in (50.0%)
            |  circumvention: /repo/src/A.kt  sed -i '' s/a/b/ src/A.kt
            |  corrupted: /repo/src/A.kt in 1a2b3c4
            |  kt_edit: 1, 0 rejected by Validation; readiness timeouts: 0
            |  Bash reads of .kt: sed -n 0, grep 0, cat 0
            |  hook denials, then: circumvention (Bash) 1
            |
            |Total over 1 session
            |  sample: 1 session over 0 days (complete at 30 sessions and 14 days)  INCOMPLETE
            |  read/search share: 1 of 2 = 50.0% (bar: at least 80%)  FAIL
            |  circumventions: 1 (bar: 0)  FAIL
            |  corrupted files: 1 (bar: 0, every commit checked)  FAIL
            |  kt_edit: 1, 0 rejected by Validation (0.0%); readiness timeouts: 0
            |  Bash reads of .kt: sed -n 0, grep 0, cat 0
            |  hook denials, then: circumvention (Bash) 1
            |FAIL
            """.trimMargin(),
            text.trimEnd(),
        )
    }

    @Test
    fun `read and search share counts Kronstadt calls against built-in reads and searches aimed at Kotlin, over the whole sample`() {
        transcripts.session("s1", "/repo/a") {
            kronstadt("kt_read_structure", "filePath" to "src/A.kt")
            kronstadt("kt_read_symbol", "symbol" to "A/f")
            kronstadt("kt_find_symbol", "query" to "A")
            read("/repo/a/src/A.kt")
            read("/repo/a/README.md") // not Kotlin
            read("/repo/a/build.gradle.kts") // .kts is not .kt
        }
        transcripts.session("s2", "/repo/b") {
            kronstadt("kt_find_usages", "symbol" to "B")
            kronstadt("kt_get_definition", "symbol" to "B", "name" to "x")
            grep("pattern" to "foo", "glob" to "**/*.kt")
            grep("pattern" to "foo", "type" to "kotlin")
            grep("pattern" to "foo", "path" to "/repo/b/src/B.kt")
            grep("pattern" to "foo", "path" to "/repo/b/src") // not aimed at Kotlin
            glob("src/**/*.{kt,kts}")
            glob("**/*.yaml") // not Kotlin
        }

        val report = analyse()

        assertEquals(listOf(3 to 1, 2 to 4), report.sessions.map { it.kronstadtReads to it.builtinKotlinReads })
        assertEquals(5, report.total.kronstadtReads)
        assertEquals(5, report.total.builtinKotlinReads)
        assertEquals(0.5, report.total.readShare)
    }

    @Test
    fun `Code Mode - calls a REPL script makes count as if the agent had made them`() {
        transcripts.session("code-mode", "/repo") {
            repl {
                kronstadt("kt_read_symbol", "symbol" to "ReadinessTracker/importState", result = "// importState in src/A.kt, L35-35\n    fun importState() = 1")
                kronstadt("kt_find_symbol", "query" to "SymbolPath", result = "IllegalStateException: Language server failed to initialize", isError = true)
                call("Read", FixtureTranscripts.obj("file_path" to "/repo/src/A.kt"), toolUseResult = FixtureTranscripts.obj("type" to "text"))
                call("Grep", FixtureTranscripts.obj("pattern" to "foo", "glob" to "*.kt"), toolUseResult = FixtureTranscripts.obj("mode" to "files_with_matches"))
                call("Glob", FixtureTranscripts.obj("pattern" to "src/**/*.kt"), toolUseResult = FixtureTranscripts.obj("numFiles" to "1"))
            }
        }
        transcripts.session("direct", "/repo") {
            kronstadt("kt_read_symbol", "symbol" to "ReadinessTracker/importState")
            kronstadt("kt_find_symbol", "query" to "SymbolPath", result = "IllegalStateException", isError = true)
            read("/repo/src/A.kt")
            grep("pattern" to "foo", "glob" to "*.kt")
            glob("src/**/*.kt")
        }

        val report = analyse()

        assertEquals(listOf(2 to 3, 2 to 3), report.sessions.map { it.kronstadtReads to it.builtinKotlinReads })
        assertEquals(listOf(0, 0), report.sessions.map { it.circumventions.size })
    }

    @Test
    fun `Code Mode - a Bash rewrite of a kt file only an earlier Bash output showed is a circumvention`() {
        transcripts.session("s", "/repo") {
            repl {
                bash("find src -type f", stdout = "src/A.kt\nsrc/B.java\n")
                bash("cat > src/A.kt <<'EOF'\nclass A\nEOF")
            }
        }

        val circumventions = analyse().sessions.single().circumventions

        assertEquals(listOf("/repo/src/A.kt" to "cat > src/A.kt <<'EOF'"), circumventions.map { it.file.toString() to it.how })
    }

    @Test
    fun `a result's content wins over its record's toolUseResult, which stands in only for empty content`() {
        transcripts.session("s", "/repo") {
            call("Bash", FixtureTranscripts.obj("command" to "ls"), "from content", toolUseResult = FixtureTranscripts.obj("stdout" to "from stdout", "stderr" to ""))
            repl {
                bash("ls", stdout = "out\n", stderr = "warning\n")
                bash("true")
                kronstadt("kt_read_symbol", "symbol" to "A", result = "class A")
                kronstadt("kt_read_symbol", "symbol" to "B", result = "No symbol 'B'", isError = true)
            }
        }

        val calls = Transcripts.load(transcripts.projects).single().calls

        assertEquals(
            listOf(
                Triple("Bash", "from content", false),
                Triple("REPL", "(dispatched #1)", false),
                Triple("Bash", "out\n\nwarning\n", false),
                Triple("Bash", "", false),
                Triple("mcp__kronstadt__kt_read_symbol", "class A", false),
                Triple("mcp__kronstadt__kt_read_symbol", "No symbol 'B'", true),
            ),
            calls.map { Triple(it.name, it.result, it.isError) },
        )
    }

    @Test
    fun `Code Mode - the REPL host call is neither Kronstadt use, nor a workaround, nor a Kotlin read`() {
        val script = "await \$`sed -i '' s/a/b/ src/A.kt`; await mcp__kronstadt__kt_read_symbol({symbol: 'A', file: 'src/A.kt'})"
        transcripts.session("only-repl", "/repo") { repl(script) }
        assertEquals(0, analyse().sessions.size) // nothing in it touches Kotlin

        transcripts.session("repl-and-read", "/repo") {
            repl(script) { bash("cat > src/A.kt <<'EOF'\nclass A\nEOF") } // the script's text is no sign src/A.kt existed
            read("/repo/src/A.kt")
        }

        val session = analyse().sessions.single()

        assertEquals(0 to 1, session.kronstadtReads to session.builtinKotlinReads)
        assertEquals(emptyList(), session.circumventions)
        assertEquals(0, session.edits)
    }
}

/** Compile outcomes by (repo, commit); anything not set up compiles. */
internal class FakeBuilds : Builds {
    private val outcomes = mutableMapOf<Pair<Path, String>, Breakage>()
    val checked = mutableListOf<Pair<Path, String>>()

    fun on(repo: String, commit: String, outcome: Breakage) {
        outcomes[Path.of(repo) to commit] = outcome
    }

    override fun breakage(repo: Path, commit: String): Breakage {
        checked += repo to commit
        return outcomes[repo to commit] ?: Breakage.Compiles
    }
}
