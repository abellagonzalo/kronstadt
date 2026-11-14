# Throwaway: real language server run (never merged)

Scripts behind the ticket "Run Kronstadt read tools against a real language server on a real repo" (issue #4).

- `probe.py <root> <plan.json> <outdir>`: raw LSP client for `kotlin-lsp --stdio`. Declares workDoneProgress, pull
  diagnostics with refresh and organize-imports code actions. Logs every server message, samples the RSS of the process
  tree, runs workspace/symbol, documentSymbol, pull diagnostics, definition and references from a plan, then exits with
  `shutdown` or by SIGKILLing the launcher, and reports which processes are still alive.
- `mcp_probe.py <kronstadt-bin> <root> <calls.json> <out.json>`: drives Kronstadt over MCP stdio with timed `tools/call`s.

Raw outputs are not committed: they name private code.
