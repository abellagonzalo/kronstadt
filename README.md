# Kronstadt

A Kotlin-only MCP server that gives coding agents (Claude Code, etc.) symbol-level access to a Kotlin codebase,
backed by a Kotlin language server. Inspired by [Serena](https://github.com/oraios/serena), specialised for Kotlin/JVM.

## Tools

| Tool | Status | What it does |
|---|---|---|
| `kt_read_structure` | ✅ | Skeleton of a file: declarations with their source header line and line span, no bodies |
| `kt_find_symbol` | ✅ | Project-wide declaration search by (partial) name, optional kind filter |
| `kt_find_usages` | planned | |
| `kt_replace_symbol` | planned | Replace a declaration by name; validate with diagnostics before writing to disk |
| `kt_insert_after` | planned | |
| `run_gradle_task` / `run_specific_test` | planned | |

## Architecture

Ports and adapters. Gradle modules make the build enforce the boundary: `core` cannot see lsp4j or the MCP SDK.

```
core/        pure Kotlin, no I/O
  domain/      Position, TextRange, TextDocument (offset math), CodeSymbol, StructureRenderer
  port/        CodeIntelligence, FileStore
  application/ ReadStructure, FindSymbol — one use case per MCP tool, returning sealed results
lsp/         CodeIntelligence over a language server (lsp4j)
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
./gradlew build          # compile + tests
./gradlew installDist    # -> server/build/install/kronstadt/bin/kronstadt
```

## Use with Claude Code

You need a Kotlin language server on your PATH, e.g. JetBrains' [kotlin-lsp](https://github.com/Kotlin/kotlin-lsp)
or [fwcd/kotlin-language-server](https://github.com/fwcd/kotlin-language-server).

```sh
claude mcp add kronstadt -- /path/to/kronstadt/server/build/install/kronstadt/bin/kronstadt \
  --language-server "kotlin-lsp --stdio"
```

Options:
- `--project-root <dir>`: project or worktree to serve (default: current directory, i.e. where Claude Code runs).
- `--language-server "<command>"`: defaults to `$KRONSTADT_LANGUAGE_SERVER`, else `kotlin-language-server`.

Each instance spawns its own language server bound to one project root, so parallel agents in separate Git
worktrees don't share state. Mind the memory: one JVM language server per agent.

## Known limitations

- Not yet run against a real Kotlin language server; only unit tests and a stdio smoke test with a failing server.
- Declaration headers are the single source line holding the symbol's name; multi-line signatures are truncated.
- Workspace symbols are empty while the language server is still indexing.
- Dependency symbols (`searchDependencies` in the original design) are filtered out.
