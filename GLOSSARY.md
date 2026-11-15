# Kronstadt

An MCP server that gives coding agents symbol-level read and write access to a Kotlin codebase, backed by a Kotlin language server.

## Language

**Symbol**:
A Kotlin declaration (class, interface, object, function, property, constructor) addressable by name.
_Avoid_: AST node, element

**Symbol Path**:
The address that names exactly one Symbol: its chain of enclosing declaration names, optionally scoped to a file, with a signature only where a name alone is ambiguous.
_Avoid_: name path, FQN, selector, locator

**Shadow Workspace**:
Kronstadt's in-memory copy of the files one edit touches, which the language server sees and validates before Write-back. It lasts only as long as that edit.
_Avoid_: shadow file system, in-memory buffer, working memory copy

**Validation**:
Checking the Shadow Workspace for errors the edit introduced, before Write-back. A failed Validation rejects the whole edit.
_Avoid_: check, lint, verify

**Write-back**:
Writing a validated edit from the Shadow Workspace to the file on disk.
_Avoid_: commit (clashes with git), flush, save, persist
