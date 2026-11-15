# Kronstadt

An MCP server that gives coding agents symbol-level read and write access to a Kotlin codebase, backed by a Kotlin language server.

## Language

**Symbol**:
A Kotlin declaration (class, interface, object, function, property, constructor) addressable by name.
_Avoid_: AST node, element

**Symbol Path**:
The address that names exactly one Symbol: its chain of enclosing declaration names, optionally scoped to a file, with a signature only where a name alone is ambiguous.
_Avoid_: name path, FQN, selector, locator

**Symbol Span**:
The full source text of a Symbol, as whole lines: its KDoc or the comments directly above it, its annotations, modifiers and declaration, and any comment at the end of its last line. It is what an agent reads and what replace and delete act on.
_Avoid_: range, body, extent

**Usage**:
A place in project source that refers to a Symbol by name, other than its own declaration. An import of the Symbol is a Usage too, but is counted apart from the others.
_Avoid_: reference, call site, occurrence

**Edit**:
One write request: an ordered batch of Operations that is validated and written back as a whole, or rejected as a whole.
_Avoid_: change, transaction, patch

**Operation**:
A single step inside an Edit (replace, insert before or after, delete, or a text replace inside a Symbol Span), aimed at one Symbol Path. A text replace may instead target a whole file, for text that belongs to no Symbol.
_Avoid_: action, command, step

**Shadow Workspace**:
Kronstadt's in-memory copy of the files one edit touches, which the language server sees and validates before Write-back. It lasts only as long as that edit.
_Avoid_: shadow file system, in-memory buffer, working memory copy

**Validation**:
Checking the Shadow Workspace for errors the edit introduced, before Write-back. A failed Validation rejects the whole edit.
_Avoid_: check, lint, verify

**Write-back**:
Writing a validated edit from the Shadow Workspace to the file on disk.
_Avoid_: commit (clashes with git), flush, save, persist
