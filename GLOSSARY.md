# Kronstadt

An MCP server that gives coding agents symbol-level read and write access to a Kotlin codebase, backed by a Kotlin language server.

## Language

**Symbol**:
A Kotlin declaration (class, interface, object, function, property, constructor) addressable by name.
_Avoid_: AST node, element

**Shadow Workspace**:
Kronstadt's in-memory view of the project's files, which the language server sees and edits are applied to before Write-back to disk.
_Avoid_: shadow file system, in-memory buffer, working memory copy

**Write-back**:
Writing a validated edit from the Shadow Workspace to the file on disk.
_Avoid_: commit (clashes with git), flush, save, persist
