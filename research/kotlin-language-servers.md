# kotlin-lsp vs kotlin-language-server for Kronstadt

Research for [issue #2](https://github.com/abellagonzalo/kronstadt/issues/2). Sources checked on 2026-11-14:
repos, source code, release notes and issue trackers. Neither server was installed or run. Items marked
**unverified** need a live check in #4.

- **kotlin-lsp**: JetBrains, [Kotlin/kotlin-lsp](https://github.com/Kotlin/kotlin-lsp). Latest release v271.2085.0, 2026-11-12.
- **KLS**: fwcd, [fwcd/kotlin-language-server](https://github.com/fwcd/kotlin-language-server). Latest release 1.3.13, 2025-01-18.

## TL;DR

KLS is deprecated by its own maintainers. It is stuck on Kotlin 2.1.0. Its `workspace/symbol` returns no ranges,
so Kronstadt's current mapping drops every result. It pushes diagnostics only for open files, and it has no
organize-imports action. **Target kotlin-lsp.** It costs more to run: a 400 MB distribution, a few GB of RAM per
instance, slow first import, alpha quality, and diagnostics that Kronstadt must pull with `textDocument/diagnostic`.

## Comparison

| Need | kotlin-lsp (JetBrains) | kotlin-language-server (fwcd) |
|---|---|---|
| Engine | IntelliJ IDEA + IntelliJ Kotlin plugin (K2 Analysis API). Supports Kotlin 2.3 ([README](https://github.com/Kotlin/kotlin-lsp#readme), [RELEASES v262.2310.0](https://github.com/Kotlin/kotlin-lsp/blob/main/RELEASES.md)) | Internal Kotlin compiler APIs (K1 `BindingContext`), pinned to Kotlin **2.1.0** ([libs.versions.toml](https://github.com/fwcd/kotlin-language-server/blob/main/gradle/libs.versions.toml), [README](https://github.com/fwcd/kotlin-language-server#readme)) |
| `textDocument/documentSymbol` | Supported. It was missing at first ([#24](https://github.com/Kotlin/kotlin-lsp/issues/24)). It now drives the VS Code outline, which shows signatures (v271.2085.0). A third-party probe in [#255](https://github.com/Kotlin/kotlin-lsp/issues/255) reports a "full, correct symbol tree" with `selectionRange`, so it returns hierarchical `DocumentSymbol`. Whether locals are included is **unverified** | Hierarchical `DocumentSymbol` (always `Either.forRight`). **Includes locals**: local functions, properties and variables ([Symbols.kt](https://github.com/fwcd/kotlin-language-server/blob/main/server/src/main/kotlin/org/javacs/kt/symbols/Symbols.kt)) |
| `workspace/symbol` | Supported. Honours `query`. An empty query returns everything, which can be 11k+ symbols ([#182](https://github.com/Kotlin/kotlin-lsp/issues/182)). Since v263.4702.0 it **also returns library symbols** (RELEASES). It returns nothing while indexing ([#182](https://github.com/Kotlin/kotlin-lsp/issues/182)). The response shape (`Location` vs `WorkspaceSymbolLocation`) is **unverified** | Returns `WorkspaceSymbol` with a **`WorkspaceSymbolLocation` (URI only, no range)** and no `workspaceSymbol/resolve` ([Symbols.kt](https://github.com/fwcd/kotlin-language-server/blob/main/server/src/main/kotlin/org/javacs/kt/symbols/Symbols.kt), [KotlinWorkspaceService.kt](https://github.com/fwcd/kotlin-language-server/blob/main/server/src/main/kotlin/org/javacs/kt/KotlinWorkspaceService.kt)). Kronstadt's `LspMapping.workspaceSymbols` skips URI-only locations, so **`kt_find_symbol` returns nothing on KLS**. Matching is fuzzy (`containsCharactersInOrder`), and it only searches source files |
| `textDocument/references` | Supported, including type aliases (v262.4739.0). Open bug: cross-module references are missing in multi-module **AGP** projects ([#210](https://github.com/Kotlin/kotlin-lsp/issues/210)). Some files return `null` for every semantic request ([#255](https://github.com/Kotlin/kotlin-lsp/issues/255)) | Supported (`referencesProvider`) ([KotlinLanguageServer.kt](https://github.com/fwcd/kotlin-language-server/blob/main/server/src/main/kotlin/org/javacs/kt/KotlinLanguageServer.kt)) |
| `textDocument/definition` | Supported, including library and JDK sources. Also `typeDefinition` and `implementation` (RELEASES v0.253.10629, v262.2310.0). Same AGP cross-module bug as above ([#210](https://github.com/Kotlin/kotlin-lsp/issues/210)) | Supported (`definitionProvider`) |
| Diagnostics | **Pull only** (`textDocument/diagnostic`). JetBrains' own Zed config says: "diagnostics do not work because they are pull-based in our implementation, but the editor expects them to be push-based" ([scripts/zed-settings.json](https://github.com/Kotlin/kotlin-lsp/blob/main/scripts/zed-settings.json)). A request for push is open ([#201](https://github.com/Kotlin/kotlin-lsp/issues/201)). Results include IntelliJ inspections. Weak warnings arrive as severity 2 (Warning), which floods agent context ([#281](https://github.com/Kotlin/kotlin-lsp/issues/281)) | **Push only** (`publishDiagnostics`), and **only for open files**. Debounced lint, 250 ms by default (`diagnostics.debounceTime`). Has no `version` and no "done" signal ([KotlinTextDocumentService.kt](https://github.com/fwcd/kotlin-language-server/blob/main/server/src/main/kotlin/org/javacs/kt/KotlinTextDocumentService.kt), [Configuration.kt](https://github.com/fwcd/kotlin-language-server/blob/main/server/src/main/kotlin/org/javacs/kt/Configuration.kt)) |
| Knowing diagnostics have settled | A pull request answers for the document's current state, so the response itself is the settle point. Watch `workDoneProgress` for import and indexing (progress is reported as a percentage, v263.4702.0). Treat the server as ready when `initialize` returns and indexing progress ends. Support for `workspace/diagnostic/refresh` and `resultId` is **unverified** | Heuristic only: wait more than `debounceTime` after the last `didChange`, then wait for the next `publishDiagnostics` for that URI. Every linted file gets a push, and clean files get an empty list (`clearDiagnostics`). But the push carries no `version`, and a later lint can supersede it, so there is no exact "settled" signal |
| Organize imports | Yes, a "Organize Imports" source action (v263.4702.0). Unused-import diagnostics are not published yet ([#201](https://github.com/Kotlin/kotlin-lsp/issues/201)) | **No**. Only two quick fixes exist: `AddMissingImportsQuickFix` and `ImplementAbstractMembersQuickFix` ([CodeAction.kt](https://github.com/fwcd/kotlin-language-server/blob/main/server/src/main/kotlin/org/javacs/kt/codeaction/CodeAction.kt)) |
| Add missing import | Yes, "Add import" quick fixes for unresolved references (v262.2310.0). Most IntelliJ intentions are also available (v263.4702.0) | Yes, `AddMissingImportsQuickFix` |
| Rename | Yes (`textDocument/rename`, v0.253.10629). Package rename and move create missing directories (v271.2085.0). It can move files and keep imports correct (v263.4702.0) | Yes (`renameProvider`, with prepare support) |
| `workspace/didChangeWatchedFiles` | Has its **own native file watcher** ("Native support of external file system changes (i.e. `git pull`)", v0.253.10629). It does not need the client to forward FS events. The watcher has crashed on macOS during init ([#154](https://github.com/Kotlin/kotlin-lsp/issues/154), open). Editing build scripts **triggers an automatic Gradle re-sync**, which an agent touching Gradle files can cause repeatedly ([#264](https://github.com/Kotlin/kotlin-lsp/issues/264)) | Handled. Create, change and delete update source files and the classpath. A changed build file triggers a classpath refresh ([KotlinWorkspaceService.kt](https://github.com/fwcd/kotlin-language-server/blob/main/server/src/main/kotlin/org/javacs/kt/KotlinWorkspaceService.kt)). The client must forward the events |
| Gradle import | Uses the Gradle Tooling API (9.8.0), with a real per-module, per-source-set model like IntelliJ. Supports Maven, Android (experimental), KMP (v271.2085.0), compiler plugins (serialization, all-open, Compose), and `languageVersion` propagation. Open bugs: one failing task in a sync empties the **whole** workspace model ([#283](https://github.com/Kotlin/kotlin-lsp/issues/283)); older multi-module bugs ([#29](https://github.com/Kotlin/kotlin-lsp/issues/29), [#189](https://github.com/Kotlin/kotlin-lsp/issues/189), the latter not reproducible on newer versions) | Runs `gradlew` with an init script (`kotlinLSPProjectDeps`) and merges everything into **one flat classpath**, so it has no module model ([GradleClassPathResolver.kt](https://github.com/fwcd/kotlin-language-server/blob/main/shared/src/main/kotlin/org/javacs/kt/classpath/GradleClassPathResolver.kt)). Kotlin DSL scripts are handled through a second task. Long-standing open issues: Kotlin DSL errors ([#397](https://github.com/fwcd/kotlin-language-server/issues/397), [#420](https://github.com/fwcd/kotlin-language-server/issues/420)), no version catalog support ([#476](https://github.com/fwcd/kotlin-language-server/issues/476)) |
| Indexing time | The first import is slow. It downloads dependencies and library sources (on by default since v263.4702.0), and `initialize` blocks until the import finishes. Claude Code's 120 s init timeout was hit in [#182](https://github.com/Kotlin/kotlin-lsp/issues/182). The workspace model and index are persisted, so later starts only validate incrementally ("only works if the project directory path is stable", JetBrains in [#182](https://github.com/Kotlin/kotlin-lsp/issues/182); v262.7569.0). JDK and library indices are shared between projects and LS instances (v261.13587.0). No published numbers | Re-runs Gradle and recompiles everything on every start. Reported at about 5 min on a real project ([#510](https://github.com/fwcd/kotlin-language-server/issues/510)). Re-indexes on every open ([#327](https://github.com/fwcd/kotlin-language-server/issues/327)) |
| Memory | Several GB per instance. Users report 2.5 to 3.2 GB per JVM ([#205](https://github.com/Kotlin/kotlin-lsp/issues/205)), and 3.6 GB for one JVM and about 11 GB across a stranded process tree ([#276](https://github.com/Kotlin/kotlin-lsp/issues/276)). After the client exits, the **JVM survives in its own process group**, reparented to launchd, so killing the launcher does not free it ([#276](https://github.com/Kotlin/kotlin-lsp/issues/276), open). Distribution is about 400 MB (v262.2310.0) | Lower, but OOMs on bigger projects ([#441](https://github.com/fwcd/kotlin-language-server/issues/441), [#352](https://github.com/fwcd/kotlin-language-server/issues/352)). Orphaned processes have been reported ([#673](https://github.com/fwcd/kotlin-language-server/issues/673)) |
| stdio | `intellij-server --stdio`, as in [nvim-lspconfig](https://github.com/neovim/nvim-lspconfig/blob/master/lsp/kotlin_lsp.lua). Also supports `--socket`. JVM stdout has been isolated from the LSP stream since v262.4739.0 | stdio is the default ([EDITORS.md](https://github.com/fwcd/kotlin-language-server/blob/main/EDITORS.md)) |
| Install on macOS | `brew install JetBrains/utils/kotlin-lsp`, a formula that symlinks `bin/intellij-server` to `kotlin-lsp` ([Formula](https://github.com/JetBrains/homebrew-utils/blob/master/Formula/kotlin-lsp.rb)). Alternatively the platform `.sit` from Releases. Bundles its own JBR 25, so no JDK is needed | `brew install kotlin-language-server` (1.3.13, MIT, [formulae.brew.sh](https://formulae.brew.sh/formula/kotlin-language-server)). Needs a JDK |
| Maintenance | Very active. Commits daily, releases every 2 to 6 weeks, about 170 open issues. **Alpha.** The repo is a **read-only mirror**: no code contributions accepted ([README](https://github.com/Kotlin/kotlin-lsp#readme)) | **Deprecated**: "There is now an official language server … this project can be considered deprecated" ([README](https://github.com/fwcd/kotlin-language-server#readme), commit of 2025-06-02). No commits since. The community fork ktlsp ([codeberg.org/winlogon/ktlsp](https://codeberg.org/winlogon/ktlsp), see [#667](https://github.com/fwcd/kotlin-language-server/issues/667)) has a 1.4.0-RC1 on Kotlin 2.2.21 |
| Licence | The repo's LICENSE.txt is Apache-2.0, and so is the brew formula. But the server is "based on … proprietary parts of JetBrains Air and Fleet products, making it partially closed-source" ([README](https://github.com/Kotlin/kotlin-lsp#readme)). The VS Code extension moved to the JetBrains Free Plugin License (v262.7569.0). The VS Code extension requires a per-user data-sharing and region choice before the server starts ([#256](https://github.com/Kotlin/kotlin-lsp/issues/256), [#251](https://github.com/Kotlin/kotlin-lsp/issues/251)). Whether the standalone `--stdio` server prompts or sends telemetry is **unverified** | MIT, fully open source |

## Kronstadt-specific consequences

1. **`kt_find_symbol` is broken on KLS today.** `LspMapping.workspaceSymbols` drops `WorkspaceSymbolLocation`
   results, and KLS never returns anything else. Supporting KLS would need a fallback, such as opening the URI and
   running `documentSymbol` to find the range. That is not worth building for a deprecated server.
2. **Diagnostics are pulled, not pushed, on kotlin-lsp.** `KronstadtLanguageClient.publishDiagnostics` would never
   fire. Write-back validation (#8) must send `textDocument/diagnostic` after `didChange`. This is better for
   Kronstadt: the response is a synchronous answer for that version, with no debounce guessing. Kronstadt must also
   advertise `textDocument.diagnostic` in its client capabilities. It must filter by severity and source, because
   IntelliJ inspections such as `FunctionName` arrive as Warnings ([#281](https://github.com/Kotlin/kotlin-lsp/issues/281)).
   Policy should block on compiler errors, not on inspection warnings.
3. **Readiness.** `initialize` may not return until the Gradle import finishes, which takes minutes on a cold
   project. Kronstadt should start the LS eagerly and advertise `window.workDoneProgress` to track indexing. Today it
   advertises neither. Tool calls made before then need an "indexing, retry" result, not an empty one.
4. **One LS per worktree is expensive.** Each new worktree path is a cold project for kotlin-lsp: the persisted
   model and index are keyed by path, while JDK and library indices are shared. Each instance costs several GB.
   Kronstadt must also kill the **process tree / JVM group** on exit, because killing `intellij-server` alone leaks
   the JVM ([#276](https://github.com/Kotlin/kotlin-lsp/issues/276)). The `--system-path` flag (seen in
   [#185](https://github.com/Kotlin/kotlin-lsp/issues/185)) sets the cache location.
5. **The server watches the disk itself.** Writing files is enough; Kronstadt does not need to send
   `didChangeWatchedFiles`. This bears on #7: a Shadow Workspace in a separate directory would be a second project
   with a second index. An in-memory overlay (`didOpen`/`didChange` without saving) is the cheap way to validate
   before Write-back. Agent edits to `build.gradle.kts` trigger re-syncs ([#264](https://github.com/Kotlin/kotlin-lsp/issues/264)).
6. **Library symbols in `workspace/symbol`.** kotlin-lsp now includes them. Kronstadt filters dependency symbols
   by design (out of scope), so keep filtering on project root.

## Recommendation

**Target JetBrains kotlin-lsp for v1** (standalone `kotlin-lsp --stdio` via `brew install JetBrains/utils/kotlin-lsp`).
Drop KLS as a supported target. At most keep it as a "may work" option in README. It is deprecated, its Kotlin is
stale, its workspace symbols lack ranges and it has no organize imports. Keep Kronstadt's LSP adapter
protocol-generic, but design diagnostics around **pull**, and design lifecycle around **slow init + several GB per
instance + JVM process-group cleanup**.

## Open risks

- **Alpha quality.** Open bugs hit Kronstadt's core reads: cross-module references and definitions in AGP projects
  ([#210](https://github.com/Kotlin/kotlin-lsp/issues/210)), whole-file analysis failures
  ([#255](https://github.com/Kotlin/kotlin-lsp/issues/255)), and a failed sync emptying the model
  ([#283](https://github.com/Kotlin/kotlin-lsp/issues/283)). #4 must check these on a real pure-JVM multi-module
  Gradle repo.
- **Memory × parallel worktrees.** At about 3 GB per LS, five agents use about 15 GB. A shared LS or a cap may be
  needed (map: "Memory budget").
- **Closed-source parts, read-only repo, and commercial direction.** JetBrains could change terms. The VS Code
  extension already moved to the Free Plugin License and has a data-sharing gate. The standalone server's runtime
  licence and telemetry behaviour need checking in the distribution's own licence files.
- **Unverified response shapes**: whether `documentSymbol` includes locals, whether `workspace/symbol` returns
  `Location` with a range, whether `diagnosticProvider` declares `workspaceDiagnostics` or `interFileDependencies`,
  and whether the server sends `workspace/diagnostic/refresh` after imports. All of these belong in #4.
- **Fallback.** If kotlin-lsp proves unusable, the only maintained alternative is the community fork ktlsp, which
  inherits KLS's architecture.
