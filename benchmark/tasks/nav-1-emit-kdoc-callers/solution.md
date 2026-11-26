`CodeWriter.emitKdoc` is called from:
- `FunSpec.emit`: `codeWriter.emitKdoc(kdocWithTags())` and `codeWriter.emitKdoc(kdoc.ensureEndsWithNewLine())`
- `PropertySpec.emit`: `codeWriter.emitKdoc(kdoc.ensureEndsWithNewLine())`
- `TypeAliasSpec.emit`: `codeWriter.emitKdoc(kdoc.ensureEndsWithNewLine())`
- `TypeSpec.emit`: `codeWriter.emitKdoc(kdocWithConstructorDocs())`, twice
