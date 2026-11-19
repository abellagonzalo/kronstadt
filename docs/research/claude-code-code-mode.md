# Claude Code "Code Mode" and what it means for Kronstadt

2026-11-18

**TL;DR.** Code Mode is real. In Claude Code 2.1.327 it is labelled "Code Mode (experimental)" and is opt-in: the gate is `CLAUDE_CODE_EXPERIMENTAL_CODE_MODE` plus an opt-in dialog. In Code Mode the model's main tool is `REPL`, one persistent, sealed JavaScript context in which built-in and MCP tools are async functions. The REPL prompt asks the model to fan calls out with `Promise.all` and to keep results in variables. A script gets an MCP tool's declared *input* schema and description, and its return type is `Promise<unknown>`. The *output* schema of an MCP tool is not passed into the script's types. MCP failures throw `McpToolError`. Kronstadt's evaluation analyser counts only top-level `tool_use` blocks, so it would see one `REPL` call and none of the `kt_*` or `Bash` calls inside it. I could not confirm whether inner calls also get their own transcript rows. A headless probe did not enable Code Mode.

Sources: the installed binary `~/.local/share/claude/versions/2.1.327`, read with `strings`, is the primary source for Claude Code. It is minified first-party code; quotes below are verbatim fragments. The MCP spec is `modelcontextprotocol.io/specification/latest/server/tools`. The page states no version, but its links point to 2026-07-28. I did not check the official Claude Code docs or changelog: two background research agents died before writing anything, so this was done inline instead.

## 1. Does Code Mode exist, and how is it enabled? — verified

- The binary contains the label `Code Mode (experimental)`, the env vars `CLAUDE_CODE_EXPERIMENTAL_CODE_MODE`, `…_RESTART` and `…_RESTARTED_FROM`, and the gate and dialog symbols `CODE_MODE_OPT_IN_GATE`, `answerCodeModeOptInDialog` and `settleCodeModeOptInAtStartup`. Telemetry names include `tengu_repl_mode_opt_in_dialog_{shown,accept,decline}`.
- The tool is named `REPL`. In one surface it is shown as `Code` (`"REPL"…"Eval"…P7t="Code"`). When `codeModeHidesToolNamesFromApi` is set, the model's tools are only `REPL` plus an async helper: *"Your two tools are `REPL` and `…`; there are no other tools. Everything you do — shell commands, file reads and edits, code search, web fetches, MCP calls — happens as JavaScript inside a `REPL` call."*
- REPL semantics, from its prompt: *"one persistent JavaScript context: variables and functions you define in one call are there in the next … but not across a restart, a resume, a fork or a wake … There is no `import`, `require` or `process`: the context is sealed."* Each part of the returned value is cut to a set number of characters, keeping the head and tail.
- MCP routing: *"this surface routes MCP tools through the REPL rather than advertising them as top-level tools."*
- **Probe:** `CLAUDE_CODE_EXPERIMENTAL_CODE_MODE=1 claude -p …` (session `2275453d-…`) got **no** REPL tool; the model reported none existed. So the env var alone does not turn it on in headless mode. The opt-in gate or dialog probably also applies. The `poll` tool from the video maps to the async helper and the "poll event" machinery, but I did not confirm its exact name.

## 2. How MCP tools appear inside the REPL — partly verified

- Calls use the tool's full name: *"MCP tools are callable by their full name (e.g. `await mcp__slack__slack_send_message({...})`)"*. They are also reachable as `tools[name](input)`, with `searchTools('words')` and `describeTool(name)` for discovery. The declaration is `declare var tools: {[name: string]: (input: Record<string, unknown>) => Promise<unknown>}`.
- **Types:** the generator for MCP tools (`function rfl`) emits only `{name, inputSchema, description}`. The generator for built-in tools also emits `outputSchema`, which becomes `BuiltinToolResults`. **An MCP tool's `outputSchema` does not reach the script's types**, so a script sees `Promise<unknown>`.
- Arguments not in the input schema are refused (`tengu_repl_mcp_undeclared_args_refused`).
- **Errors:** *"Built-in tools resolve with `{error: string}` on failure; MCP tool calls THROW on failure."* The error class is `McpToolError`, and *"e.message carries the tool error, e.detail the parsed body when it was JSON."* This applies when the `tengu_repl_mcp_error_throw` flag is on.
- **Value returned:** unverified. Calls from a script are classed as "program" calls (`isMcpProgramCall` is true for `innerCall`). The program-call path documents: *"content is then the server's own content blocks as MCP defines them … text blocks beside structuredContent stay … The size check that protects a model's context is skipped too."* So a script probably receives the raw content blocks and `structuredContent`, without the context-window truncation. I couldn't run it to see the actual shape (see §1, probe).

## 3. Concurrency and limits — partly verified

- The prompt encourages parallel calls: *"the work inside a script (shell commands, file reads, searches, tool calls) is comparatively free, and runs concurrently when you fan it out with `Promise.all`."* I found no evidence of per-server serialization.
- Limits exist: `tengu_repl_tool_call_cap` (a per-script tool-call cap of unknown size) and, in modes that ask for permission, *"The script is stopped … ms after it starts, and that time includes time spent waiting at permission prompts."*
- **Kronstadt today:** reads are serialized in places by `LspCodeIntelligence.syncLock` (`lsp/src/main/kotlin/kronstadt/lsp/LspCodeIntelligence.kt:90`), `DeferredCodeIntelligence.opening` (`server/.../DeferredCodeIntelligence.kt:68`) and `WriteLock` (`core/.../application/WriteLock.kt:11`). I have not measured how a 20-way `Promise.all` behaves against them.

## 4. Transcripts and the evaluation analyser — analyser side verified, transcript side unverified

- `evaluation/src/main/kotlin/kronstadt/evaluation/Transcripts.kt:71-86` builds `ToolCall`s only from `assistant` records' `tool_use` blocks, and pairs each with the `tool_result` that has the same `tool_use_id`. `Analyser.kt:66` recognises Kronstadt tools by the name `^mcp__(?:.*_)?kronstadt__(kt_\w+)$`. `Circumventions.kt:27` matches `"Bash"` calls.
- In Code Mode the assistant emits one `REPL` `tool_use` whose input is JavaScript. Inner calls get synthetic ids (`toolUseId: \`${v}${Fct}${Qe}\``, `innerCallHostToolUseId`, `innerCallIndex`). I found no evidence either way that inner calls are written as separate JSONL rows. **If they are not, the analyser reports zero `kt_*` usage and zero Bash circumventions for Code Mode sessions.** If they are, they would have no `assistant` record of their own, so `Transcripts.kt` would still miss them. Either way the analyser needs a change; how much depends on the real transcript shape.
- None of the local transcripts under `~/.claude/projects/` contain a `REPL` tool_use, because Code Mode has never been on for this user.

## 5. Do tool descriptions still reach the model? — verified

Yes, but in a different form. MCP tools are not declared to the API as top-level tools. The model finds them through `searchTools('words')`, which returns `{name, about, input}`. `about` is the description cut to about 160 characters (`wu=160`), and `input` is a printed input schema with a character budget. `describeTool(name)` returns the full description and input schema. I didn't check how server instructions reach the model in Code Mode.

## 6. MCP spec facts — verified (spec page, inferred version 2026-07-28)

- `structuredContent`: *"returned as a JSON value in the `structuredContent` field … that conforms to the tool's `outputSchema` if one is defined."* *"For backwards compatibility, a tool that returns structured content SHOULD also return the serialized JSON in a TextContent block."*
- `outputSchema`: *"Servers **MUST** provide structured results that conform to this schema. Clients **SHOULD** validate."* Claude Code's MCP client enforces this: *"Tool … has an output schema but did not return structured content"* is thrown when `structuredContent` is missing on a successful call.
- No pagination is defined for tool *results*; only `tools/list` is paginated.
- Execution errors use `isError: true` in the result, and clients SHOULD pass them to the model. Protocol errors are JSON-RPC `error` objects. Kronstadt already returns `isError` results (`server/.../KronstadtTools.kt:536`). Kronstadt returns no `structuredContent` and declares no `outputSchema` today.

## Claims from the video I could not verify

- `spawn_context`, `list_contexts`, `release_context`: these strings are **not** in 2.1.327. A `contexts` global with `result()`, `state.lastText` and `tail()` does exist (*"contexts['…']: auto mode's safety review did not approve this context's report…"*), so script-launched sub-contexts seem real under other names.
- "~50% fewer input tokens", "71% of the cost": I found no first-party numbers.
- A tool named `poll`: I found poll-event machinery but didn't confirm the tool name.
- That Code Mode is broadly rolled out: in this build it is experimental and opt-in.

## Implications for Kronstadt (limited to what the evidence supports)

1. **Output shape:** a script gets `unknown` and must dig through content blocks. Adding `structuredContent`, with the text kept per the spec's SHOULD, makes results usable by scripts. An `outputSchema` buys validation from Claude Code's client but **no script typing** (§2), and it makes `structuredContent` mandatory on every successful call.
2. **Usage cap and paging:** on the program-call path Claude Code skips its own context truncation (§2), so whatever bound Kronstadt applies is the only bound. The 100-Usage cap still protects scripts that return results whole. Since the spec defines no result paging, paging would be Kronstadt's own convention, for example an `offset` input plus a `next` field.
3. **Evaluation analyser:** it will miss Code Mode usage (§4). Fixing it needs a real Code Mode transcript first.
4. **Concurrency:** fan-out is the default the REPL prompt teaches (§3). Kronstadt's locks are worth a load test.

## Open: needs a real Code Mode session

Turn Code Mode on interactively (env var plus accepting the opt-in), then run one script that calls `mcp__kronstadt__kt_find_symbol({query: "SymbolPath"})` and returns `typeof r`, `Object.keys(r)` and the first 1500 characters of the JSON. Then inspect that session's JSONL. That settles §2 (the returned value) and §4 (transcript rows).

## Side finding

During the probe, the second Kronstadt instance in the same worktree failed: `Language server 'kotlin-lsp --stdio' failed to initialize: While lock file: ~/Library/Caches/kronstadt/index/<worktree>/kotlin-server/rocks/v241/LOCK: Resource temporarily unavailable`. Two Claude Code sessions in one worktree, or a session plus a headless `claude -p`, cannot both use Kronstadt. This is related to the per-worktree index directory (#33).
