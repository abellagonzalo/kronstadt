These classes implement `Taggable` directly:
- `AnnotationSpec`, `FileSpec`, `FunSpec`, `ParameterSpec`, `PropertySpec`, `TypeAliasSpec`, `TypeSpec` and `TypeName`
  delegate to their `tagMap` (`Taggable by tagMap`), built from the builder's tags.
- `TagMap`, an internal value class, implements it itself by overriding `tags`.
