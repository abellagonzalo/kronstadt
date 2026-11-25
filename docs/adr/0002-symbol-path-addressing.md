# Address Symbols with Symbol Paths, resolved exactly through documentSymbol

Agents name a Symbol with a Symbol Path, which every Kronstadt tool accepts and every read tool prints. Segments are separated by `/` (`Outer/Nested/run`), and a file is optional. Without a file, a dotted package may come first (`com.example.a/Config`). The package prefix must contain at least one dot: a single-word first segment is always a declaration name, so `fixture/Shape` means `Shape` inside a declaration `fixture`. To pick between Symbols in single-word packages, pass the file. A path may give only the trailing segments, or start with `/` to anchor at the top level. Overloads are told apart by a parameter-type signature, added only when a name alone is ambiguous: `parse(String)`. The lsp adapter extracts each Symbol's Signature and extension receiver from kotlin-lsp's `documentSymbol.detail`, and core matches a path's signature and receiver against those extracted fields after whitespace is normalised. Extension receivers are written Kotlin-style inside the segment (`String.shout`). A bare name prefers a symbol that isn't a function, and parentheses select only functions and constructors, so `size` is the property and `size(Int)` the function. Matching is exact. Several matches return an error listing every candidate's path; Kronstadt never picks one.

We use `/` and not Kotlin's `.` because `.` already separates packages, marks extension receivers, and appears in nested type names inside signatures (`Map.Entry`). With `/` between segments, a dot inside a segment always means one of those three things.

Resolution always goes through `documentSymbol`, even when no file is given: `workspace/symbol` only supplies candidate files. kotlin-lsp's index is not a faithful view of the symbols. It returns top-level symbols twice, drops a property when a function shares its name, never shows extension receivers, and lists local functions, which `documentSymbol` omits. It also stops at 200 results without saying so (#38), counting dependency symbols and names that merely contain the query. It lists exact name matches first, so a full page that ends in another name still holds every file declaring the name. A full page that ends in the name may leave one out, and resolving from what came back could then pick one. So such a path fails without a file and asks for one. A package prefix doesn't help, because the index is searched by the name alone.

## Consequences

- Symbols that can't be addressed: accessors (edits target the whole property), local declarations and lambdas (edits target the enclosing function), and library or JDK symbols.
- Special forms: constructors are `Box/constructor(Int)`. Companions are `X/Companion/…`, or the companion's own name if it has one. Enum entries are `Color/BLUE`.
- The receiver is part of an extension's name: `shout` does not match `String.shout` (the miss suggests it), so the two never compete.
- The non-function preference settles a tie only between declarations in the same container. A property in one class and a function in another stay ambiguous, so Kronstadt still never picks between unrelated Symbols.
- When the preference decides a bare name, `kt_read_symbol`'s header and an `edit_in` whose `old` doesn't occur name the kind it picked and the Symbol Paths of the functions it passed over, so an agent that meant a function can name it (#107).
- The lsp adapter reads receivers from the start of kotlin-lsp's `detail` (`String.(): String`, `String.: Char`) and Signatures from its parameter parentheses. It is the only code that knows that format, which the contract test pins.
- Signature and receiver matching therefore depend on the format of kotlin-lsp's `detail` string, which is another reason for the tested-version warning in ADR 0001.

## Considered options

- **Serena's positional overload index (`parse[1]`)**: rejected because the index shifts when an overload is added, so in parallel worktrees it silently points at the wrong symbol.
- **Line numbers**: rejected because any edit invalidates them.
- **Fully qualified names resolved by the index**: rejected because the index is lossy (see above) and empty until indexing finishes.
- **Substring matching on the last segment (Serena)**: rejected because it lets edits hit the wrong symbol.

Sources: [#6 How does an agent address a Symbol unambiguously?](https://github.com/abellagonzalo/kronstadt/issues/6), [#3 Prior art: Serena](https://github.com/abellagonzalo/kronstadt/issues/3), [#4 Run Kronstadt read tools against a real language server](https://github.com/abellagonzalo/kronstadt/issues/4).
