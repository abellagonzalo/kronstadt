# Disk is the source of truth; the Shadow Workspace lives for one tool call

The file on disk is always authoritative. Kronstadt keeps no document state between tool calls. A write tool loads the files it touches from disk, opens them in the language server (`didOpen`), applies and validates the edit through `didChange`, writes it back, then sends `didSave` and `didClose`. That transient set of open documents is the Shadow Workspace. Read tools follow the same rule: every tool closes every document it opened before it returns.

kotlin-lsp treats an open document as authoritative over disk, so a document left open stops tracking edits made outside Kronstadt. These come from the user's IDE, Claude's Edit tool, shell commands, and `git checkout` or rebase in the worktree. Closing everything at the end of each call hands those files back to kotlin-lsp's own file watcher. Kronstadt therefore needs no watcher, no mtime polling, no reload logic and no conflict state.

Write-back is guarded:
- Kronstadt writes only if the file's current content hash still matches the bytes the edit was based on. On a mismatch it rejects the edit with "changed on disk since read; re-read and retry". It never merges.
- It writes through a temp file and an atomic rename, keeping the file's permissions.
- Write tools run one at a time under a single lock per Kronstadt instance. Read tools don't take the lock.

## Consequences

- An edit made outside Kronstadt can be missing from `workspace/symbol` (`kt_find_symbol`) for a few seconds, until kotlin-lsp's watcher re-indexes it. A live test measured about 5 s for one file. We accept this lag. A watcher or check-on-use can be added later if evaluation shows the lag hurts.
- No pending or validated edit survives a tool call. A multi-step edit that passes through broken intermediate states must be sent as one batch in a single call. A begin/commit flow across calls is not possible in v1.
- We don't know whether kotlin-lsp rereads the disk as soon as it gets `didSave`/`didClose`. An acceptance test must show that `kt_find_symbol` sees an edit immediately after Write-back. If it doesn't, the fallback is to keep the written file open until the next call touches it. Settled in [#25](https://github.com/abellagonzalo/kronstadt/issues/25): kotlin-lsp 271.2085.0 doesn't (neither does `workspace/didChangeWatchedFiles`), so `LspCodeIntelligence` keeps a written document open after `didSave` for as long as later calls read that same text from disk. The first call that reads different text (an outside change) syncs it and closes the document, handing the file back to the watcher. Until a call touches it, an outside change to that one file is invisible to kotlin-lsp.

## Considered options

- **Long-lived Shadow Workspace plus a native file watcher**: rejected. A watcher alone has a race: a call can arrive before the event does. It also brings debouncing for `git checkout` storms and stale or conflict states.
- **Long-lived Shadow Workspace plus check-on-use (mtime/hash before every call)**: rejected for v1. It is correct but adds reload and conflict logic that nothing needs while no state survives a call.
- **Shadow Workspace as a separate directory on disk**: rejected. kotlin-lsp would index it as a second project, which costs a cold index of several GB.

Sources: [#7 Disk or Shadow Workspace: which is the source of truth?](https://github.com/abellagonzalo/kronstadt/issues/7), [#4 Run Kronstadt read tools against a real language server](https://github.com/abellagonzalo/kronstadt/issues/4), [#3 Prior art: Serena](https://github.com/abellagonzalo/kronstadt/issues/3).
