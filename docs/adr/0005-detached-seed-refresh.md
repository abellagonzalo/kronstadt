# The Index Seed is refreshed by a detached process

Claude Code never closes an MCP server's stdin when a session ends. It sends SIGINT, then SIGTERM 0.1 s later, then SIGKILL about 0.4 s after that, about 0.5 s in all. It signals only the server's own process, not its children. kotlin-lsp needs about 0.5 s after `exit` to close its index and release the RocksDB locks, so Kronstadt can't wait for it and clone the index before it is killed. Measured in [#52](https://github.com/abellagonzalo/kronstadt/issues/52#issuecomment-7003646808).

So Kronstadt only starts the refresh. A JVM shutdown hook runs on those signals and on the exit after a closed stdin alike. If kotlin-lsp is ready, the hook sends `shutdown`, waits up to 200 ms for the answer, and sends `exit`. If `shutdown` was answered, it starts a child JVM with Kronstadt's classpath under `nohup` (`DetachedSeedRefresh`) and returns. Claude Code doesn't signal that child, and `nohup` keeps it alive when the terminal closes. The child waits up to 10 s for kotlin-lsp's process to end, killing it and seeding nothing if it doesn't. Then it runs `IndexSeeds.refresh`, which waits for the locks, clones the index and swaps it in atomically. It logs to `last-refresh.log` next to the seed slot, because nobody reads Kronstadt's stderr any more by then.

## Considered options

- **Refresh in the shutdown hook, with shorter waits.** Rejected: waiting for kotlin-lsp alone uses up the 0.5 s, so SIGKILL would cut almost every refresh short.
- **Seed at the next start, from a sibling's exited index.** Rejected: nothing would tell an index closed cleanly from one whose kotlin-lsp was killed.

## Consequences

- The child isn't kotlin-lsp's parent, so it can't read kotlin-lsp's exit code. "Clean" now means `shutdown` was answered and the process then ended by itself within the wait. A kotlin-lsp that crashed after answering would still be seeded. RocksDB recovers from a crash, and the swap stays atomic.
- A kill at any point, of Kronstadt or of the child, still leaves the previous seed whole: the slot is only ever replaced by the atomic swap. A half-made clone left by a killed child is a hidden sibling of the slot. A refresh never deletes the seed it swaps out, because `clonefile(2)` of a directory deleted mid-clone succeeds with only part of it: it retires it under a timed name, and startup pruning deletes such leftovers once over 10 minutes old ([#53](https://github.com/abellagonzalo/kronstadt/issues/53)).
- The child is not in a session of its own (macOS has no `setsid` command), so a signal to Kronstadt's whole process group, such as Ctrl-C on a `claude -p` run from a shell, ends it too. That costs only the refresh.
- The child waits only for a process with kotlin-lsp's pid and start instant: kotlin-lsp may have exited, and its pid been reused, before the child's JVM is up.
- For about a second after a session ends, a small JVM (`-Xmx64m`) runs on.

Sources: [#52 Index Seed: make sure the refresh happens when Claude Code exits](https://github.com/abellagonzalo/kronstadt/issues/52), [ADR 0003](0003-disk-is-source-of-truth.md).
