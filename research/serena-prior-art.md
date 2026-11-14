# Prior art: how Serena addresses, edits and validates symbols

Research for [#3](https://github.com/abellagonzalo/kronstadt/issues/3). All claims are from Serena's source at commit
[`6b39542`](https://github.com/oraios/serena/tree/6b39542bb108ce77901072deac699d770b302d52) (main, 2026-11-14) or
from its GitHub issues. Links below use `S/` as shorthand for
`https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/`.

## TL;DR

- **Addressing**: a *name path* is the chain of `documentSymbol` names inside one file (`Outer/Inner/method`).
  Patterns can be a bare name, a relative suffix (`Class/method`) or absolute (`/Class/method`). Overloads get a
  0-based `[i]` suffix in declaration order, assigned only when siblings share a name. Substring matching applies to
  the last segment only. Every edit tool also takes a file path; the symbol has to be unique within that file.
- **Edits** are plain text splices over the LSP `DocumentSymbol.range` ("body") that the server reports. No AST, no
  re-indentation, no formatting. The edit goes to the server as an in-memory `didChange` and is then written straight
  to disk.
- **Validation**: none. A "diagnostics diff after edit" path exists but is **turned off**
  (`ENABLE_DIAGNOSTICS = False`). Serena's reason: edits often break the code on purpose and later edits fix it.
  Diagnostics are only available as on-demand read tools.
- **Sync**: files are opened per operation with `didOpen` and a whole-file `didChange` if the mtime changed, then
  closed with `didClose`. Before most symbolic calls, external changes are found by polling (`os.stat` every tracked
  file, compare mtimes) and sent as `workspace/didChangeWatchedFiles`. There is no file watcher and no `didSave`.
- **Kotlin**: JetBrains' pre-alpha [kotlin-lsp](https://github.com/Kotlin/kotlin-lsp) (IntelliJ-based), with no
  Kotlin-specific range or name fixes. Most of the code is about keeping the server alive: builds that expire, index
  lock clashes between concurrent instances, a wait for indexing to finish, and CI crashes.
- **Pitfalls in the issue tracker** are dominated by one class of bug: `replace_symbol_body` reports `OK` but silently
  corrupts the file because the server's `range` doesn't cover what the agent thinks the "body" is. Examples are a
  leading keyword or modifier, or a variable whose range is only its name.

## 1. Symbol addressing

### Name paths
- Defined and matched in `NamePathMatcher`
  ([S/src/serena/symbol.py#L145-L219](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/symbol.py#L145-L219)).
  The separator is `/`. A symbol's name path is its name plus its ancestors' names, stopping at the File symbol
  (`iter_name_path_components_reversed`, symbol.py L349-360). So a name path is **file-relative**, not a
  package-qualified FQN.
- Pattern forms (docstring of `find_symbol`,
  [S/src/serena/tools/symbol_tools.py#L97-L135](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/tools/symbol_tools.py#L97-L135)):
  - `method`: any symbol with that name, at any depth.
  - `Class/method`: suffix match, so it also matches `Outer/Class/method`.
  - `/Class/method`: absolute. It must match the whole path within the file.
- Matching walks the pattern components from the right against the symbol's ancestors (`matches_reversed_components`).
- **Substring matching** (`substring_matching=True`) applies only to the *last* component (`use_substring_matching =
  self._substring_matching and (i == 0)`). Ancestors always need an exact match.
- Names come from the server verbatim. Serena can normalize them per server (`_normalize_symbol_name`,
  [S/src/solidlsp/ls.py#L2043-L2063](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/ls.py#L2043-L2063)),
  for example by stripping parameter lists. The Kotlin server class does **not** override it. A name that contains
  `/` breaks addressing entirely ([#1797](https://github.com/oraios/serena/issues/1797), Erlang `foo/1`).

### Overloads
- The index is assigned while converting the raw symbols
  ([S/src/solidlsp/ls.py#L2172-L2203](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/ls.py#L2172-L2203)).
  Among the siblings under one parent, a symbol gets `overload_idx` only if its name occurs more than once. The index
  is the 0-based declaration order.
- In a pattern, `[i]` constrains only the components where it is written. If it is omitted, any overload matches
  (`PatternComponent.matches`). Whether `Foo/bar` should match `Foo[0]/bar` was decided as "no"
  ([#935](https://github.com/oraios/serena/issues/935)). Overload support was added later
  ([#515](https://github.com/oraios/serena/issues/515)).
- Consequences: the index is **positional and unstable**. Inserting an overload before another one renumbers it. The
  `rename_symbol` docstring still says overloads "may have to include a method's signature", which is out of date
  (symbol_tools.py L444-445).

### Uniqueness and scoping
- Every edit and reference tool takes `relative_path` (a file) plus `name_path` and calls `find_unique`
  ([S/src/serena/symbol.py#L769-L799](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/symbol.py#L769-L799)).
  - No match is an error.
  - With several matches, the one whose full name path *equals* the pattern wins if there is exactly one. Otherwise
    the tool errors and lists all candidates as JSON, which the agent can use to retry.
- `find_symbol` can search a file, a directory or the whole project. It also takes `include_kinds` and
  `exclude_kinds` (LSP `SymbolKind` integers), `depth` (how many levels of children to include), `include_body`,
  `include_info` (hover text) and `max_matches`. Exceeding `max_matches` raises an error that lists the matching paths
  (lsp_api.py L526-L531).
- Project-wide search does **not** use `workspace/symbol`. It walks the filesystem and sends a `documentSymbol`
  request for every file (`request_full_symbol_tree`,
  [S/src/solidlsp/ls.py#L2210](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/ls.py#L2210)).
  Results go into a pickled on-disk cache keyed by file path and content hash
  ([ls.py#L1971-L2016](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/ls.py#L1971-L2016)).
  A stale-cache bug was reported in [#1593](https://github.com/oraios/serena/issues/1593).

### Body vs identifier positions
- **Body** is `location.range`, the full `DocumentSymbol.range`. **Location** (line and column) is
  `selectionRange.start`, the identifier
  ([S/src/serena/symbol.py#L269-L340](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/symbol.py#L269-L340)).
  The identifier position feeds references, rename and definition. The body range feeds edits.

## 2. Edit tools

All of them live in `CodeEditor`
([S/src/serena/code_editor.py](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/code_editor.py)).
Each one opens the file through the language server's buffer, splices the text, and every splice sends a
range-based `didChange` (ls.py L1484-1560). On exiting the context, `write_file_atomic` writes the result to disk
(code_editor.py L82-97).

| Tool | Range touched | Whitespace handling |
|---|---|---|
| `replace_symbol_body` (L110-128) | Deletes exactly `[range.start, range.end)` and inserts the new body at `range.start` | `body.strip()`. The first line lands at the original start column. Later lines are inserted verbatim (absolute indentation is the caller's job). No re-indentation and no formatter. |
| `insert_after_symbol` (L146-185) | Column 0 of the line after `range.end.line` | Forces a trailing newline. Leading blank lines are `max(caller's, 1 if the symbol is a Function/Method/Class/Interface/Struct else 0)`. Refuses to run when the body equals the name (that is, the range covers only the name). |
| `insert_before_symbol` (L187-213) | Column 0 of `range.start.line` | Same blank-line heuristic, applied after the inserted text |
| `safe_delete_symbol` (lsp_api.py L768-805, code_editor.py L266-296) | `[range.start, range.end)`, then deletes the blank lines that follow | Calls `textDocument/references` first and refuses if any are found (returns them by file and line) |
| `rename_symbol` (L409-444) | `textDocument/rename` at the identifier, then applies the returned `WorkspaceEdit` itself, one file at a time; file renames go through `os.rename` | Not atomic across files. Fails if the server returns nothing or zero changes. |

**Docstrings and annotations** are whatever the server puts in `range`. Serena makes no attempt of its own. The tool
description says the body "may or may not include a preceding docstring or other preceding annotations" depending on
the language (symbol_tools.py L371-373). For Kotlin, KDoc and annotation inclusion therefore depends on kotlin-lsp.
Serena has no Kotlin regression tests for it: its Kotlin tests only cover symbol tree, references, bare names and file
diagnostics
([S/test/solidlsp/kotlin/](https://github.com/oraios/serena/tree/6b39542bb108ce77901072deac699d770b302d52/test/solidlsp/kotlin)).

**Per-server range patches** show the general problem. For gopls and TypeScript, Serena extends ranges so they
include the leading keyword (`type`, `export const`) and the trailing semicolon
([S/src/solidlsp/language_servers/gopls.py#L209-L250](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/language_servers/gopls.py#L209-L250),
[typescript_language_server.py#L700-L740](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/language_servers/typescript_language_server.py#L700-L740)).
Each patch followed a silent-corruption bug report.

**Positions** are Python code-point offsets sent as LSP `character` without converting to UTF-16
([#2064](https://github.com/oraios/serena/issues/2064), open). Edits after a character outside the Basic
Multilingual Plane, such as an emoji, land in the wrong column. The Kotlin client advertises `positionEncodings:
["utf-16"]` regardless.

**JetBrains backend**: a paid alternative uses a JetBrains IDE plugin instead of LSP. That path reads and writes
file text itself and calls `refresh_file` on the IDE afterwards (code_editor.py L446-530). The rename there supports
renaming in comments and text occurrences too.

## 3. Language server ↔ disk sync

- **Per-operation open/close.** `open_file` keeps a reference-counted `LSPFileBuffer`
  ([S/src/solidlsp/ls.py#L95-L231](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/ls.py#L95-L231),
  [L1407-L1460](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/ls.py#L1407-L1460)):
  - The first open reads the file from disk and sends `didOpen`.
  - Re-opening when the mtime changed sends a full-text `didChange` with a bumped version.
  - When the last reference goes away, it sends `didClose`.
  - Files are **not** kept open between tool calls.
- **Edits** send range-based `didChange` for every delete and insert, then write the file atomically. Serena never
  sends `didSave`, even though the Kotlin client advertises `didSave: true`.
- **External edits** (other editors, other agents, git checkout, builds) are found by `LanguageServerFileChangeNotifier`
  ([S/src/serena/ls_manager.py#L298-L410](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/ls_manager.py#L298-L410)):
  - It walks every tracked source file, `stat`s it and diffs mtimes against the last snapshot.
  - It sends `workspace/didChangeWatchedFiles` with Created, Changed and Deleted events.
  - For created files it also sends `didCreateFiles` and an open/close cycle, because "didChangeWatchedFiles(Created)
    alone is not enough for every backend".
  - It runs synchronously before reference, rename and delete calls (`ls_sync_file_system_changes`, lsp_api.py L558-560
    and L764, L778). `find_symbol` skips it and relies on the mtime and content-hash checks of the buffer and cache.
  - Cost: one `stat` per tracked file per call, which hurt on a repository with 187k files
    ([#2077](https://github.com/oraios/serena/issues/2077)).
- **Indexing readiness** (Kotlin): after `initialized`, Serena waits up to 120 s for either `intellij/ready-for-test`
  or for every `$/progress` token to end
  ([kotlin_language_server.py#L549-L668](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/language_servers/kotlin_language_server.py#L549-L668)).
  After that it allows a fixed 1 s buffer for cross-file references.

## 4. Diagnostics

- **Edits are not validated.** `EditingToolWithDiagnostics.ENABLE_DIAGNOSTICS = False`
  ([S/src/serena/tools/tools_base.py#L433-L455](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/tools/tools_base.py#L433-L455)):
  > "The feature is currently disabled, because per-edit diagnostics are a questionable feature, since individual edits
  > often intentionally introduce diagnostics (e.g. function signature mismatches or even syntax errors) that are then
  > resolved in subsequent edits."
- When enabled, the dormant design
  ([S/src/serena/lsp/lsp_diagnostics.py](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/lsp/lsp_diagnostics.py))
  works like this:
  - Before the edit, it snapshots warnings and errors (severity ≤ 2) for the edited file.
  - After the edit, it waits up to 2.5 s for a newer `publishDiagnostics` generation, falling back to a pull
    `textDocument/diagnostic`.
  - It reports only **new** diagnostics, identified by message, range, severity, code and source, grouped as
    file → severity → owning symbol's name path.
  - It is purely informational and runs after the write. It never blocks or rolls back, and it covers only the edited
    file, not dependants.
- **Read tools** (enabled):
  - `get_diagnostics_for_file` takes a line range and a minimum severity.
  - `get_diagnostics_for_symbol` (optional) can also cover every referencing symbol, which Serena's answer to "did my
    signature change break callers?" is meant to address.
  - Diagnostics are fetched with pull `textDocument/diagnostic` first, then the cached or awaited push diagnostics
    ([ls.py#L988-L1065](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/ls.py#L988-L1065)).
  - The Kotlin server enables diagnostics with a 250 ms debounce (`initializationOptions.diagnostics`). Serena has a
    Kotlin file-diagnostics test.

## 5. Kotlin specifics

- **Server**: JetBrains' [kotlin-lsp](https://github.com/Kotlin/kotlin-lsp), downloaded and managed by Serena
  ([S/src/solidlsp/language_servers/kotlin_language_server.py](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/solidlsp/language_servers/kotlin_language_server.py)).
  Serena's docs call it "pre-alpha ... some issues may appear"
  ([docs/01-about/020_programming-languages.md](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/docs/01-about/020_programming-languages.md)).
  It does not use fwcd/kotlin-language-server.
- **Expiring builds**: the pinned version is `261.13587.0`, with this comment: *"We have now switched back to the
  initial version, because all newer versions keep expiring after a short period of time"* (L58-63). Builds exit with
  "This build of intellij-server has expired" ([#2008](https://github.com/oraios/serena/issues/2008)). The docs still
  say the default is `263.4702.0`, so docs and code disagree.
- **Concurrent instances**:
  - The IntelliJ index is locked per project. Serena claims a separate storage and system directory with a file lock
    and falls back to a per-PID directory ([#1966](https://github.com/oraios/serena/issues/1966)).
  - From 271.x the index moved to a per-project folder under the user cache, keyed by project path. That folder
    ignores `idea.system.path` and needs `initializationOptions.indexDir`; the bug is still open
    ([#2266](https://github.com/oraios/serena/issues/2266)).
  - Also seen: leftover fallback directories ([#2112](https://github.com/oraios/serena/issues/2112)), zombie JVMs
    ([#1081](https://github.com/oraios/serena/issues/1081)), and leaked `/tmp/idea-system*` directories
    ([#1087](https://github.com/oraios/serena/issues/1087)).
- **Robustness hacks**:
  - Serena must answer the server's `workspace/*/refresh` requests. Otherwise the client replies "MethodNotFound",
    *"which the Kotlin LSP treats as fatal and shuts itself down"* (L608-L615).
  - Defaults are `-Xmx2G` and a 120 s indexing wait.
  - Kotlin is quarantined in Serena's CI because it "crashes on JVM restart under CI memory limits"
    ([test/conftest.py#L534](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/test/conftest.py#L534)).
- **Symbol behavior seen in tests**:
  - A data class `Model` is reported as kind **Struct**, with `body_location` start line equal to end line.
  - There is no hover info for it ([#924](https://github.com/oraios/serena/issues/924)).
  - The `getDiagnostics`, `references` and `definition` tests pass. Nothing tests Kotlin edits or Kotlin overloads.
- `get_symbols_overview` defaults to depth 1 for Java and Kotlin (classes and their members), but 0 for other
  languages (symbol_tools.py L63-64).

## 6. Pitfalls reported in issues and docs

1. **Range ≠ body causes silent corruption**, all reported as `OK`:
   - Go `type type X` ([#1529](https://github.com/oraios/serena/issues/1529)).
   - TS `export const export const` ([#1956](https://github.com/oraios/serena/issues/1956),
     [#1029](https://github.com/oraios/serena/issues/1029)).
   - Pyright variables whose range is only the name, leaving `X = new = old`
     ([#2226](https://github.com/oraios/serena/issues/2226), open).
   - `insert_after_symbol` landing inside a multi-line dict ([#1360](https://github.com/oraios/serena/issues/1360)).

   After a corrupting edit, the server's symbol tree for that file can go empty, so the next edits fail with "No symbol
   matching" (#1529).
2. **Whitespace drift**: delete after insert leaves extra blank lines
   ([#1697](https://github.com/oraios/serena/issues/1697)). Replacing can drop separator whitespace
   ([#1952](https://github.com/oraios/serena/issues/1952)). Earlier versions tried to re-indent and produced
   over-indentation ([#187](https://github.com/oraios/serena/issues/187)); today they don't re-indent at all.
3. **Rename completeness depends on the server**: ZLS left out edits in unopened files, and Serena applied the partial
   edit and reported success ([#1744](https://github.com/oraios/serena/issues/1744)).
4. **UTF-16 column mismatch** ([#2064](https://github.com/oraios/serena/issues/2064)).
5. **Stale symbols** from the cache ([#1593](https://github.com/oraios/serena/issues/1593)), and the per-call cost of
   the freshness poll ([#2077](https://github.com/oraios/serena/issues/2077)).
6. **Agent adoption**: Serena's Claude Code context marks `Read` and `Edit` as FORBIDDEN for code and spends a whole
   prompt arguing against the agent's "preference" for built-in tools. It notes that Claude Code's `Edit` refuses
   files not read through `Read`, so after reading through Serena the agent *has to* edit through Serena
   ([S/src/serena/resources/config/contexts/claude-code.yml](https://github.com/oraios/serena/blob/6b39542bb108ce77901072deac699d770b302d52/src/serena/resources/config/contexts/claude-code.yml)).
   Serena also ships a Claude Code hook (`serena/hooks.py`) that auto-approves its edit tools in `acceptEdits` and
   `auto` modes, because otherwise every MCP edit prompts the user.

## Implications for Kronstadt

1. **Name paths (#6)**: copy the shape: file-scoped `/`-separated paths, suffix and absolute forms, and errors that
   list the candidates. Two changes:
   - Overloads: use the **parameter type signature** (`foo(Int, String)`) instead of a positional `[i]`, because the
     index is unstable across edits. Accept `[i]` only as a fallback, if at all.
   - Allow omitting the file and resolving by FQN or package, which Serena cannot do.

   Check what names kotlin-lsp actually returns for overloads, extension functions (`String.foo`), companion objects
   and property accessors. Serena does no normalization for Kotlin, so this is unexplored ground.
2. **Write tools (#9)**: the biggest lesson is that the server's `range` is not a safe edit boundary. Kronstadt should
   do one of two things:
   - Define "body" itself, for example from the declaration's start including modifiers, annotations and KDoc to the
     end of the range, and verify it with the parser or PSI.
   - Or at least check that the replaced text round-trips: re-request `documentSymbol` after the edit and confirm the
     symbol still exists with the expected name and kind.

   Also add regression tests for Kotlin KDoc, annotations, `data class` one-liners, expression-bodied functions and
   properties with initializers. Serena never tested any of these for Kotlin. Make the indentation rule explicit
   (Serena: strip, then place at the original column, no re-indent). Convert columns to UTF-16 from day one.
3. **Diagnostics policy (#8)**: Serena tried per-edit diagnostics and turned them off, because multi-step edits pass
   through states that don't compile. This is direct evidence against "reject the write if diagnostics get worse". If
   Kronstadt keeps "validate before Write-back" through a Shadow Workspace, it needs an escape hatch, such as:
   - only blocking on *syntax* errors (parse failures, an empty symbol tree after the edit);
   - reporting new semantic errors without blocking;
   - batching several edits into one validated transaction.

   Diff against the pre-edit diagnostics, as Serena does, so existing errors don't block. Expect to wait for push
   diagnostics: Serena budgets 2.5 s and kotlin-lsp debounces by 250 ms.
4. **find_usages/get_definition output (#10)**: Serena maps every reference location to its **containing symbol**
   with `documentSymbol` plus a range lookup, and returns the name path plus a snippet. It includes Python-specific
   hacks for references that have no containing symbol, and needs care to filter imports and self-references
   (ls.py L2511-2674). That output is useful to agents and worth copying. Group by file → containing name path, and
   decide how to treat import lines explicitly. Reference completeness depends on the server having finished indexing,
   so expose an "indexing" state instead of silently returning partial results.
5. **Sync**: per-call mtime polling plus `didChangeWatchedFiles` works but scales with repository size. Kronstadt
   serves one worktree per instance, so it can poll the same way, or use a real file watcher plus content hashes.
   Send `didSave` after Write-back. kotlin-lsp advertises it, and an IntelliJ-based server may defer work until save
   (this needs checking).
6. **Language server choice and operations**: kotlin-lsp is the only serious option Serena found, but it is
   operationally fragile:
   - Builds expire, so pin a version with a known-good expiry and make it configurable. Kronstadt already lets the
     user provide the server.
   - Answer every server-to-client request (`workspace/*/refresh`, `workDoneProgress/create`), or the server shuts
     down.
   - Wait for `intellij/ready-for-test` or for `$/progress` to finish.
   - Memory: about 2 GB of heap per instance. With N worktrees that is N JVMs, which feeds the "Memory budget" item.
   - Different worktree paths get separate index directories (they are keyed by project path), so parallel worktrees
     shouldn't collide. Two instances on the *same* path will, from 271.x on, unless `indexDir` is set.
7. **Adoption (evaluation item)**: expect to fight Claude Code's preference for Read and Edit, and its "must Read
   before Edit" rule. Serena's answer is heavy prompting plus hooks. Kronstadt's tool descriptions and any Claude Code
   hooks need the same thought.
