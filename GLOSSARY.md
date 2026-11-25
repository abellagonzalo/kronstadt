# Kronstadt

An MCP server that gives coding agents symbol-level read and write access to a Kotlin codebase, backed by a Kotlin language server.

## Language

**Symbol**:
A Kotlin declaration (class, interface, object, function, property, constructor) addressable by name.
_Avoid_: AST node, element

**Symbol Path**:
The address that names exactly one Symbol: its chain of enclosing declaration names, optionally scoped to a file, with a signature only where a name alone is ambiguous.
_Avoid_: name path, FQN, selector, locator

**Signature**:
The parameter types of a function or constructor, e.g. `(String, Int)`, written in a Symbol Path only to tell overloads apart. It excludes the receiver and the return type.
_Avoid_: parameter list, type, overload key

**Symbol Span**:
The full source text of a Symbol, as whole lines: its KDoc or the comments directly above it, its annotations, modifiers and declaration, a list separator (`,`) right after it, and any comment at the end of its last line. It is what an agent reads and what replace and delete act on.
_Avoid_: range, body, extent

**Usage**:
A place in project source that refers to a Symbol by name, other than its own declaration. An import of the Symbol is a Usage too, but is counted apart from the others.
_Avoid_: reference, call site, occurrence

**Edit**:
One write request: an ordered batch of Operations that is validated and written back as a whole, or rejected as a whole. An Edit can amend the last Rejected Edit: its Operations are appended to the Rejected Edit's, and the combined Edit is validated and written back as one.
_Avoid_: change, transaction, patch

**Operation**:
A single step inside an Edit (replace, insert before or after, delete, or a text replace inside a Symbol Span), aimed at one Symbol Path. A text replace may instead target a whole file, for text that belongs to no Symbol. A create makes a new file instead: its whole text, validated and written back with the rest of the Edit.
_Avoid_: action, command, step

**Shadow Workspace**:
Kronstadt's in-memory copy of the files one edit touches, which the language server sees and validates before Write-back. It lasts only as long as that edit.
_Avoid_: shadow file system, in-memory buffer, working memory copy

**Validation**:
Checking the Shadow Workspace for errors the edit introduced in the files it touched, before Write-back. A failed Validation rejects the whole edit. Errors in other files are Collateral Errors.
_Avoid_: check, lint, verify

**Rejected Edit**:
An Edit that failed Validation, kept in memory until the next write call so a follow-up can amend it. Nothing of it is written. An amend that fails through no fault of the Rejected Edit (one of its own Operations fails, the index isn't ready, or it names another id) keeps it under the same id; any other write call drops it, as does an amend whose Rejected Edit no longer applies, is refused because a file changed on disk, is written, or times out in Validation.
_Avoid_: pending edit, draft

**Collateral Error**:
A compiler error an edit introduces in a file it didn't touch, such as a caller still using a signature the edit changed. It is reported with the edit's result and never blocks it.
_Avoid_: caller error, ripple error, downstream error

**Write-back**:
Writing a validated edit from the Shadow Workspace to the file on disk.
_Avoid_: commit (clashes with git), flush, save, persist

**Index Seed**:
A repo's snapshot of a language-server index, taken after an instance's language server exits cleanly with a complete index, from which a new worktree starts instead of indexing from scratch.
_Avoid_: template, cache, warm index

**Retired Seed**:
An Index Seed that a refresh has replaced, kept under a timed hidden name beside the seed slot until startup pruning deletes it, because a worktree may still be cloning it.
_Avoid_: old seed, stale seed, backup

**Temporary Clone**:
A clone of an index made under a timed hidden name next to its destination and then moved into place, so the destination is never left half-copied. A killed instance can leave one behind, for seeding or pruning to delete.
_Avoid_: temp dir, scratch copy, staging copy
