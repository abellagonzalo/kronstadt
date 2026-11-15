# Kronstadt

A Kotlin-only MCP server that gives coding agents (Claude Code, etc.) symbol-level access to a Kotlin codebase,
backed by JetBrains' [kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) (see ADR 0001). Inspired by [Serena](https://github.com/oraios/serena), specialised for Kotlin/JVM.

## Tools

| Tool | Status | What it does |
|---|---|---|
| `kt_read_structure` | ✅ | Skeleton of a file: declarations with their source header line and line span, no bodies |
| `kt_find_symbol` | ✅ | Project-wide declaration search by (partial) name, optional kind filter |
| `kt_find_usages` | planned | |
| `kt_replace_symbol` | planned | Replace a declaration by name; validate with diagnostics before writing to disk |
| `kt_insert_after` | planned | |

## Architecture

Ports and adapters. Gradle modules make the build enforce the boundary: `core` cannot see lsp4j or the MCP SDK.

```
core/        pure Kotlin, no I/O
  domain/      Position, TextRange, TextDocument (offset math), CodeSymbol, StructureRenderer
  port/        CodeIntelligence, FileStore
  application/ ReadStructure, FindSymbol — one use case per MCP tool, returning sealed results
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

kotlin-lsp starts as soon as Kronstadt does and imports the project in the background. `kt_find_symbol` waits until
the import has finished and no indexing is in progress, also after a later re-import. If that takes longer than the
readiness timeout, it fails with a retry error rather than returning partial results.

Each instance spawns its own language server bound to one project root, so parallel agents in separate Git
worktrees don't share state. Mind the memory: one JVM language server per agent.

## Known limitations

- Declaration headers are the single source line holding the symbol's name; multi-line signatures are truncated.
- Dependency symbols (`searchDependencies` in the original design) are filtered out.
