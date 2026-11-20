## Kotlin code

Use Kronstadt for every change to a `.kt` file, production and test code alike, including new files: create them with `kt_edit`'s `create` Operation. Use another tool only when a Kronstadt tool failed or gave a wrong result. If that happens, say so, use the other tool for that one change only, and open an issue for the bug at the end.

## Agent skills

### Issue tracker

Issues and specs live in this repo's GitHub Issues (`gh` CLI). See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `GLOSSARY.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
