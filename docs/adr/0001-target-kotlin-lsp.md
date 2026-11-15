# Target JetBrains kotlin-lsp as the only language server

Kronstadt v1 targets JetBrains' kotlin-lsp and nothing else. fwcd's kotlin-language-server and its community fork ktlsp are unsupported. fwcd's server is deprecated, has no organize-imports action, and its `workspace/symbol` returns locations without ranges, so symbol search finds nothing. ktlsp inherits that design.

Because there is one target, the `lsp/` adapter may rely on kotlin-lsp specifics: the `intellij/workspaceImportState` readiness signal, pull-only diagnostics, the `kotlin.organize.imports` command, and `documentSymbol.detail` for signatures. The `CodeIntelligence` port in `core` stays server-neutral.

`--language-server` / `$KRONSTADT_LANGUAGE_SERVER` remain, but only to choose *which kotlin-lsp* runs. The default is `kotlin-lsp --stdio`. Kronstadt records a tested kotlin-lsp version and warns, without refusing to run, when the server reports a different one. kotlin-lsp is partly closed source, so Kronstadt never bundles or redistributes it: users install it themselves.

## Consequences

- We accept the alpha status of kotlin-lsp, its memory footprint (around 1 GB warm, around 5 GB while cold indexing), and breakage between builds. The tested-version warning is the first line of defence.
- Supporting another server later means auditing every kotlin-lsp-specific path in the adapter.

## Considered options

- **fwcd kotlin-language-server / ktlsp**: rejected (see above).
- **Plain LSP only, server-agnostic adapter**: rejected because it gives up readiness detection and overload signatures for a portability we don't need.

Sources: [#2 Compare kotlin-lsp vs kotlin-language-server](https://github.com/abellagonzalo/kronstadt/issues/2), [#4 Run Kronstadt read tools against a real language server](https://github.com/abellagonzalo/kronstadt/issues/4).
