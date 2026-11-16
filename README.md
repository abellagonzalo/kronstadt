# Kronstadt

A Kotlin-only MCP server that gives coding agents (Claude Code, etc.) symbol-level access to a Kotlin codebase,
backed by JetBrains' [kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) (see ADR 0001). Inspired by [Serena](https://github.com/oraios/serena), specialised for Kotlin/JVM.

## Tools

| Tool | Status | What it does |
|---|---|---|
| `kt_read_structure` | ✅ | Skeleton of a file: declarations with their canonical Symbol Path, signature and line span, no bodies |
| `kt_find_symbol` | ✅ | Declaration search by (partial) name in project source, optional kind filter; results grouped by file with their canonical Symbol Path and line span |
| `kt_read_symbol` | ✅ | One declaration's exact source (KDoc, annotations, body), addressed by Symbol Path, e.g. `Calculator/parse(String)`; the file is optional (`com.example.a/Config` narrows by package) |
| `kt_find_usages` | ✅ | Every Usage of a Symbol in main and test source, grouped by file and by the canonical Symbol Path containing it; imports only counted, at most 100 Usages in whole files, `file` shows one file |
| `kt_get_definition` | ✅ | Where a name used inside a Symbol is declared (`at` picks one occurrence): a project declaration's KDoc and declaration without the body, a local's enclosing Symbol, or a library's fully qualified name, jar and KDoc and declaration, read-only |
| `kt_edit` | 🚧 | Replace a declaration's whole Symbol Span, re-indented to fit; written only if it adds no compiler errors (one `replace` per call so far; batches and the other Operations are planned) |

## Architecture

Ports and adapters. Gradle modules make the build enforce the boundary: `core` cannot see lsp4j or the MCP SDK.

```
core/        pure Kotlin, no I/O
  domain/      Position, TextRange, TextDocument (offset math), PackageHeader (a file's package), CodeSymbol, StructureRenderer,
               SymbolPath, SymbolTree (resolution, canonical paths), SymbolSpan, FileLayout (re-indent),
               Diagnostic, Validation (new-error diff)
  port/        CodeIntelligence, FileStore
  application/ ReadStructure, FindSymbol, ReadSymbol, Edit — one use case per MCP tool, returning sealed results;
               ShadowWorkspace (documents one Edit opens), WriteLock
lsp/         CodeIntelligence over kotlin-lsp (lsp4j), incl. readiness tracking
workspace/   FileStore over the local disk, confined to the project root
server/      MCP stdio server: tool schemas -> use cases; composition root (Main.kt)
```

Rules of thumb:
- Testable with plain values → `domain`. Coordinates ports → `application`. Protocol details → adapters.
- Use cases return sealed results (e.g. `NotKotlin`, `OutsideProject`) that the MCP layer turns into actionable
  messages. Infrastructure failures (language server down, timeouts) become tool errors.
- stdout is reserved for MCP. `Main.kt` redirects `System.out` to stderr so no library can corrupt the stream.

## Build

Requires JDK 25.

```sh
./gradlew build                # compile + fast tests
./gradlew :lsp:contractTest    # slow contract tests against a real kotlin-lsp (Seam 2)
./gradlew installDist          # -> server/build/install/kronstadt/bin/kronstadt
```

The contract tests import and index the fixture project in `lsp/src/test/fixture` with the kotlin-lsp on your PATH
(or `$KRONSTADT_LANGUAGE_SERVER`).

## Use with Claude Code

You need [kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) installed; Kronstadt never bundles it. Kronstadt is
tested against kotlin-lsp 271.2085.0 and warns on stderr when the server reports another version.

```sh
claude mcp add kronstadt -- /path/to/kronstadt/server/build/install/kronstadt/bin/kronstadt
```

Options:
- `--project-root <dir>`: project or worktree to serve (default: current directory, i.e. where Claude Code runs).
- `--language-server "<command>"`: which kotlin-lsp to run. Defaults to `$KRONSTADT_LANGUAGE_SERVER`, else
  `kotlin-lsp --stdio`.
- `--readiness-timeout <seconds>`: how long tools that need the index wait for kotlin-lsp to finish importing and
  indexing. Defaults to `$KRONSTADT_READINESS_TIMEOUT`, else 90.

kotlin-lsp starts as soon as Kronstadt does and imports the project in the background. `kt_find_symbol`, `kt_find_usages` and `kt_get_definition` wait until
the import has finished and no indexing is in progress, also after a later re-import. If that takes longer than the
readiness timeout, it fails with a retry error rather than returning partial results.

Each instance spawns its own language server bound to one project root, so parallel agents in separate Git
worktrees don't share state. Mind the memory: one JVM language server per agent.

## Known limitations

- Declaration headers are the single source line holding the symbol's name; multi-line signatures are truncated.
- Dependency symbols (`searchDependencies` in the original design) are filtered out.
