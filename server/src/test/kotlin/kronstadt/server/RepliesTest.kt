package kronstadt.server

import kronstadt.core.application.CollateralErrors
import kronstadt.core.application.EditInScope
import kronstadt.core.application.EditResult
import kronstadt.core.application.EditedFile
import kronstadt.core.application.EditedSymbol
import kronstadt.core.application.FindUsagesResult
import kronstadt.core.application.GetDefinitionResult
import kronstadt.core.application.IndentationHint
import kronstadt.core.application.LookupFailure
import kronstadt.core.application.OperationFailure
import kronstadt.core.application.Problem
import kronstadt.core.application.RenameResult
import kronstadt.core.application.Snippet
import kronstadt.core.application.SourceLine
import kronstadt.core.application.ValidationFailure
import kronstadt.core.application.WriteBackFailure
import kronstadt.core.domain.PreferredOverFunctions
import kronstadt.core.domain.SymbolCandidate
import kronstadt.core.domain.SymbolKind
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the tools tell the agent, built from core's result values. */
class RepliesTest {
    private val a = SymbolCandidate("Greeter/greet", "src/A.kt")
    private val b = SymbolCandidate("greet", "src/B.kt")
    private val typeMismatch = Problem("src/A.kt", "greet", 5, "Type mismatch", "\"hello\"")
    private val ambiguity = Problem("src/B.kt", null, 7, "Overload resolution ambiguity", "Greeter().hello()")

    /** kt_edit's Reply to [result], for Operations of [types], in a call that amended [amend]. */
    private fun edit(result: EditResult, vararg types: String, amend: String? = null): Reply = Replies.edit(result, types.toList(), amend)

    /** kt_edit's Reply to Operation [index] of [types] failing for [failure]. */
    private fun operationFailed(index: Int, failure: OperationFailure, vararg types: String, rejectedEdit: String? = null): Reply =
        edit(EditResult.OperationFailed(index, failure, rejectedEdit = rejectedEdit), *types, amend = rejectedEdit)

    /** An `edit_in`'s 'old' that doesn't occur in [scope], with [hint] if it occurs with indentation ignored. */
    private fun missing(scope: EditInScope, old: String, hint: IndentationHint? = null) =
        OperationFailure.NotExactlyOnce(scope, Snippet.Miss(old, 0, emptyList(), hint))

    // Lookup failures: one wording for every tool.

    @Test
    fun `an invalid path quotes it and the reason`() {
        assertEquals(
            "Invalid Symbol Path 'a/(b': unbalanced parenthesis",
            Replies.lookupFailure(LookupFailure.InvalidPath("a/(b", "unbalanced parenthesis"), ToolAdvice.READ_SYMBOL),
        )
    }

    @Test
    fun `an ambiguous path lists the candidates, with their files when resolved project-wide`() {
        assertEquals(
            "'greet' matches 2 symbols in src/A.kt; pass one of:\n  Greeter/greet\n  greet",
            Replies.lookupFailure(LookupFailure.Ambiguous("greet", "src/A.kt", listOf(a, b)), ToolAdvice.READ_SYMBOL),
        )
        assertEquals(
            "'greet' matches 2 symbols; pass one of these paths with its file:\n  Greeter/greet — src/A.kt\n  greet — src/B.kt",
            Replies.lookupFailure(LookupFailure.Ambiguous("greet", null, listOf(a, b)), ToolAdvice.EDIT),
        )
    }

    @Test
    fun `kt_find_usages, resolving project-wide, asks for one of the paths with its file as declaredIn`() {
        assertEquals(
            "'greet' matches 2 symbols; pass one of these paths with its file as declaredIn:\n" +
                "  Greeter/greet — src/A.kt\n  greet — src/B.kt",
            Replies.lookupFailure(LookupFailure.Ambiguous("greet", null, listOf(a, b)), ToolAdvice.FIND_USAGES),
        )
    }

    @Test
    fun `kt_find_usages, resolving in declaredIn, words its failures as the other tools do in a file`() {
        assertEquals(
            "'greet' matches 2 symbols in src/A.kt; pass one of:\n  Greeter/greet\n  greet",
            Replies.lookupFailure(LookupFailure.Ambiguous("greet", "src/A.kt", listOf(a, b)), ToolAdvice.FIND_USAGES),
        )
        assertEquals(
            "No symbol 'gret' in src/A.kt. Use kt_read_structure to list its declarations.",
            Replies.lookupFailure(LookupFailure.NotFound("gret", "src/A.kt", emptyList()), ToolAdvice.FIND_USAGES),
        )
    }

    @Test
    fun `a missing Symbol lists close names, or says which tool to look with`() {
        assertEquals(
            "No symbol 'gret' in src/A.kt. Close names:\n  Greeter/greet",
            Replies.lookupFailure(LookupFailure.NotFound("gret", "src/A.kt", listOf(a)), ToolAdvice.READ_SYMBOL),
        )
        assertEquals(
            "No symbol 'gret'. Close names:\n  Greeter/greet — src/A.kt",
            Replies.lookupFailure(LookupFailure.NotFound("gret", null, listOf(a)), ToolAdvice.GET_DEFINITION),
        )
        assertEquals(
            "No symbol 'gret' in src/A.kt. Use kt_read_structure to list its declarations.",
            Replies.lookupFailure(LookupFailure.NotFound("gret", "src/A.kt", emptyList()), ToolAdvice.EDIT),
        )
        assertEquals(
            "No symbol 'gret'. Use kt_find_symbol to search by partial name.",
            Replies.lookupFailure(LookupFailure.NotFound("gret", null, emptyList()), ToolAdvice.READ_SYMBOL),
        )
    }

    @Test
    fun `kt_find_usages, resolving project-wide, offers declaredIn for a missing Symbol too`() {
        assertEquals(
            "No symbol 'gret'. Use kt_find_symbol to search by partial name, or, if you know the file that declares it, " +
                "pass it as declaredIn.",
            Replies.lookupFailure(LookupFailure.NotFound("gret", null, emptyList()), ToolAdvice.FIND_USAGES),
        )
    }

    @Test
    fun `too many declarations asks for the file, as declaredIn for kt_find_usages`() {
        val cause = "'greet' can't be resolved without a file: so many declarations are named 'greet', dependencies' " +
            "included, that the project index stops at a maximum number of results and may not list them all; "
        assertEquals(cause + "pass its file", Replies.lookupFailure(LookupFailure.TooManyDeclarations("greet", "greet"), ToolAdvice.RENAME_SYMBOL))
        assertEquals(
            cause + "pass the file that declares it as declaredIn",
            Replies.lookupFailure(LookupFailure.TooManyDeclarations("greet", "greet"), ToolAdvice.FIND_USAGES),
        )
    }

    @Test
    fun `an untrustworthy span says to retry, to read the enclosing Symbol, or to edit_in the whole file`() {
        assertEquals(
            "Index out of sync for greet in src/A.kt: its name is not where kotlin-lsp says; retry",
            Replies.lookupFailure(LookupFailure.OutOfSync("greet", "src/A.kt"), ToolAdvice.READ_SYMBOL),
        )
        assertEquals(
            "Box/width shares line 3 of src/A.kt with other code, so it has no whole-line span; read the enclosing symbol instead",
            Replies.lookupFailure(LookupFailure.SharedLine("Box/width", "src/A.kt", 3), ToolAdvice.READ_SYMBOL),
        )
        assertEquals(
            "Box/width shares line 3 of src/A.kt with other code, so it has no whole-line span; use a whole-file edit_in " +
                "instead: file 'src/A.kt' with no symbol, and an 'old' of exact text from that file",
            Replies.lookupFailure(LookupFailure.SharedLine("Box/width", "src/A.kt", 3), ToolAdvice.EDIT),
        )
        assertEquals(
            "Box/constructor(Int, Int) shares line 1 of src/A.kt with other code, so it has no whole-line span; use a " +
                "whole-file edit_in instead: file 'src/A.kt' with no symbol, and an 'old' of exact text from that file",
            Replies.lookupFailure(LookupFailure.SharedLine("Box/constructor(Int, Int)", "src/A.kt", 1), ToolAdvice.EDIT),
        )
    }

    @Test
    fun `a file that can't be looked in says why, naming the tool for a non-Kotlin one`() {
        assertEquals("File not found: src/Missing.kt", Replies.lookupFailure(LookupFailure.FileNotFound("src/Missing.kt"), ToolAdvice.READ_STRUCTURE))
        assertEquals(
            "Not a Kotlin file: README.md. kt_find_usages only supports .kt and .kts files.",
            Replies.lookupFailure(LookupFailure.NotKotlin("README.md"), ToolAdvice.FIND_USAGES),
        )
        assertEquals("Path is outside the project root: ../B.kt", Replies.lookupFailure(LookupFailure.OutsideProject("../B.kt"), ToolAdvice.EDIT))
    }

    @Test
    fun `a file that can't be looked in names the argument it was passed as, when the tool takes two`() {
        assertEquals(
            Reply.error("declaredIn: File not found: src/Missing.kt"),
            Replies.findUsages(FindUsagesResult.LookupFailed(LookupFailure.FileNotFound("src/Missing.kt"), FindUsagesResult.FileArgument.DECLARED_IN)),
        )
        assertEquals(
            Reply.error("file: Path is outside the project root: ../B.kt"),
            Replies.findUsages(FindUsagesResult.LookupFailed(LookupFailure.OutsideProject("../B.kt"), FindUsagesResult.FileArgument.FILE)),
        )
    }

    @Test
    fun `a message becomes a sentence more can follow`() {
        assertEquals("File not found: src/A.kt.", Replies.sentence("File not found: src/A.kt"))
        assertEquals("No symbol 'x'. Use kt_find_symbol to search by partial name.", Replies.sentence("No symbol 'x'. Use kt_find_symbol to search by partial name."))
        assertEquals("No symbol 'x'. Close names:\n  y\n", Replies.sentence("No symbol 'x'. Close names:\n  y"))
    }

    // Validation and Write-back failures: one wording for kt_edit and kt_rename_symbol.

    @Test
    fun `a rejected Edit lists its new errors with their files and offers its id to amend`() {
        assertEquals(
            Reply.error(
                "Edit rejected (id: e1): 1 new error; nothing written.\n" +
                    "  src/A.kt:L5 in greet: Type mismatch\n" +
                    "      \"hello\"\n" +
                    "Fix with kt_edit amend: \"e1\" and only the fixing Operations, or resend the whole batch.",
            ),
            edit(EditResult.ValidationFailed(ValidationFailure.Rejected(listOf(typeMismatch)), "e1")),
        )
    }

    @Test
    fun `a rejected change without an id, as a rename, lists its new errors and asks to fix what causes them`() {
        assertEquals(
            Reply.error(
                "Rename rejected: 2 new errors; nothing written.\n" +
                    "  src/A.kt:L5 in greet: Type mismatch\n" +
                    "      \"hello\"\n" +
                    "  src/B.kt:L7 in (file level): Overload resolution ambiguity\n" +
                    "      Greeter().hello()\n" +
                    "Fix what causes them and retry.",
            ),
            Replies.rename(RenameResult.ValidationFailed(ValidationFailure.Rejected(listOf(typeMismatch, ambiguity))), "greet", "hello"),
        )
    }

    @Test
    fun `a Validation that timed out says nothing was written`() {
        assertEquals(Reply.error("validation timed out; nothing written"), edit(EditResult.ValidationFailed(ValidationFailure.TimedOut)))
        assertEquals(
            Reply.error("validation timed out; nothing written"),
            Replies.rename(RenameResult.ValidationFailed(ValidationFailure.TimedOut), "greet", "hello"),
        )
    }

    @Test
    fun `a file changed on disk is to be re-read`() {
        assertEquals(
            Reply.error("src/A.kt changed on disk since read; re-read and retry"),
            edit(EditResult.WriteBackFailed(WriteBackFailure.ChangedOnDisk("src/A.kt"))),
        )
    }

    @Test
    fun `a partial Write-back names the file that failed, why, and those already written`() {
        assertEquals(
            Reply.error(
                "Write-back failed part-way: writing src/C.kt failed (No space left on device). " +
                    "Already written, not rolled back: src/A.kt, src/B.kt. " +
                    "Nothing else was written; re-read these files before retrying.",
            ),
            edit(EditResult.WriteBackFailed(WriteBackFailure.PartiallyWritten(listOf("src/A.kt", "src/B.kt"), "src/C.kt", "No space left on device"))),
        )
    }

    @Test
    fun `a file renamed onto an existing one names both`() {
        assertEquals(
            Reply.error(
                "src/a/Greeter.kt would be renamed to src/a/Host.kt, which already exists; nothing written. " +
                    "Pick another name, or move that file first.",
            ),
            Replies.rename(RenameResult.WriteBackFailed(WriteBackFailure.TargetExists("src/a/Greeter.kt", "src/a/Host.kt")), "Greeter", "Host"),
        )
    }

    // kt_edit

    @Test
    fun `a failure that ends on a list is followed by Nothing written on its own line`() {
        val miss = Snippet.Miss("re", 3, listOf(SourceLine(3, "fun greet(): Int = 1"), SourceLine(4, "fun regret(): Int = 2")))

        assertEquals(
            Reply.error(
                "Operation 0: 'old' occurs 3 times in A.kt; make it longer so it occurs exactly once:\n" +
                    "  L3: fun greet(): Int = 1\n  L4: fun regret(): Int = 2\nNothing written.",
            ),
            operationFailed(0, OperationFailure.NotExactlyOnce(EditInScope(null, "A.kt"), miss), "edit_in"),
        )
    }

    @Test
    fun `a failure that ends on a list starts Nothing written and the rejected Edit's suffix on its own line`() {
        val miss = Snippet.Miss("e", 4, listOf(SourceLine(1, "package a"), SourceLine(3, "fun greet(): Int = Bravo.one")))

        assertEquals(
            Reply.error(
                "Operation 0: 'old' occurs 4 times in A.kt; make it longer so it occurs exactly once:\n" +
                    "  L1: package a\n  L3: fun greet(): Int = Bravo.one\n" +
                    "Nothing written; the rejected Edit e1 is still kept: amend it again with kt_edit amend: \"e1\" and only the fixing Operations.",
            ),
            operationFailed(0, OperationFailure.NotExactlyOnce(EditInScope(null, "A.kt"), miss), "edit_in", rejectedEdit = "e1"),
        )
    }

    @Test
    fun `a written Edit that broke a caller in another file lists it under Collateral Errors`() {
        val written = EditedFile("A.kt", listOf(EditedSymbol("greet", 3, 3)), emptyList(), 0, emptyList(), 0)
        val mismatch = Problem("B.kt", "use", 5, "Return type mismatch.", "fun use(): Int = greet() + 1")

        assertEquals(
            Reply.result(
                "Edited A.kt\n" +
                    "  greet  // L3-3\n" +
                    "Collateral Errors: 1 new error in files this Edit didn't change, which Validation doesn't check. " +
                    "Nothing was blocked: the Edit is written; fix them with another Edit.\n" +
                    "  B.kt:L5 in use: Return type mismatch.\n" +
                    "      fun use(): Int = greet() + 1",
            ),
            edit(EditResult.Written(listOf(written), CollateralErrors(listOf(mismatch))), "replace"),
        )
    }

    @Test
    fun `the result lists the imports the Edit made unused, which it removed`() {
        val written = EditedFile("A.kt", emptyList(), emptyList(), 0, emptyList(), 0, importsRemoved = listOf("b.Bravo", "c.Charlie"))

        assertEquals(
            Reply.result("Edited A.kt\nRemoved imports it made unused:\n  b.Bravo\n  c.Charlie"),
            edit(EditResult.Written(listOf(written)), "edit_in"),
        )
    }

    @Test
    fun `the result lists the errors already in the file up to the cap, counts the rest, and says they may be false`() {
        val errors = (1..5).map { Problem("A.kt", "broken", 5, "Error $it", "fun broken(): Int = x") }
        val written = EditedFile("A.kt", listOf(EditedSymbol("greet", 3, 3)), emptyList(), 0, errors, morePreexistingErrors = 2)

        assertEquals(
            Reply.result(
                "Edited A.kt\n" +
                    "  greet  // L3-3\n" +
                    "Errors already in the file:\n" +
                    (1..5).joinToString("") { "  L5 in broken: Error $it\n      fun broken(): Int = x\n" } +
                    "  (2 more)\n" +
                    "They may be false: kotlin-lsp can lag behind edits made outside Kronstadt. Check with the compiler or tests before acting on them.",
            ),
            edit(EditResult.Written(listOf(written)), "edit_in"),
        )
    }

    @Test
    fun `an edit_in whose old starts inside a line's indentation fails, naming the line`() {
        assertEquals(
            Reply.error(
                "Operation 0: 'old' starts inside line 4's indentation in A.kt; start it at the beginning of the line or after " +
                    "the indentation. Nothing written.",
            ),
            operationFailed(0, OperationFailure.StartsInIndentation(EditInScope(null, "A.kt"), 4), "edit_in"),
        )
        assertEquals(
            Reply.error(
                "Operation 0: 'old' starts inside line 4's indentation in greet in A.kt; start it at the beginning of the line " +
                    "or after the indentation. Nothing written.",
            ),
            operationFailed(0, OperationFailure.StartsInIndentation(EditInScope("greet", "A.kt"), 4), "edit_in"),
        )
    }

    @Test
    fun `an edit_in whose old matches only with different indentation fails, saying where and how it differs`() {
        val old = "  val n = 1\n  return n"

        assertEquals(
            Reply.error(
                "Operation 0: 'old' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if indentation " +
                    "is ignored: the file indents it by 4 spaces, 'old' by 2 spaces. Copy 'old' from kt_read_symbol output. " +
                    "Nothing written.",
            ),
            operationFailed(0, missing(EditInScope("greet", "A.kt"), old, IndentationHint(4, IndentationHint.Indents(4, "    ", "  "))), "edit_in"),
        )
        assertEquals(
            Reply.error(
                "Operation 0: 'old' doesn't occur in A.kt exactly as written, but occurs once at line 4 if indentation is " +
                    "ignored, where its lines are indented differently from the file's. Read the file to copy it. Nothing written.",
            ),
            operationFailed(0, missing(EditInScope(null, "A.kt"), "\tval n = 1\n  return n", IndentationHint(4, null)), "edit_in"),
        )
    }

    @Test
    fun `an edit_in whose old doesn't occur says any line break matches any line ending`() {
        assertEquals(
            Reply.error(
                "Operation 0: 'old' doesn't occur in greet in A.kt, exactly as written (whitespace included; any line break " +
                    "matches any line ending). Use kt_read_symbol to see its source. Nothing written.",
            ),
            operationFailed(0, missing(EditInScope("greet", "A.kt"), "val n = 2\n    return n"), "edit_in"),
        )
    }

    @Test
    fun `an edit_in missing in a property a bare name chose over a same-named function names its kind and the function`() {
        val property = EditInScope("edit", "A.kt", PreferredOverFunctions(SymbolKind.PROPERTY, listOf("edit(Int)")))

        assertEquals(
            Reply.error(
                "Operation 0: 'old' doesn't occur in edit (the property) in A.kt, exactly as written (whitespace included; any line " +
                    "break matches any line ending). Use kt_read_symbol to see its source. A function shares its name: edit(Int). " +
                    "Nothing written.",
            ),
            operationFailed(0, missing(property, "return n"), "edit_in"),
        )
        assertEquals(
            Reply.error(
                "Operation 0: 'old' doesn't occur in edit (the property) in A.kt exactly as written, but occurs once at line 4 if " +
                    "indentation is ignored: the file indents line 5 by 8 spaces, 'old' by 2 spaces. Copy 'old' from kt_read_symbol " +
                    "output. A function shares its name: edit(Int). Nothing written.",
            ),
            operationFailed(0, missing(property, "val edit =\n  0", IndentationHint(4, IndentationHint.Indents(5, "        ", "  "))), "edit_in"),
        )
        assertEquals(
            Reply.error(
                "Operation 0: 'old' doesn't occur in edit(Int) in A.kt, exactly as written (whitespace included; any line break " +
                    "matches any line ending). Use kt_read_symbol to see its source. Nothing written.",
            ),
            operationFailed(0, missing(EditInScope("edit(Int)", "A.kt"), "val edit"), "edit_in"),
        )
    }

    @Test
    fun `an insert whose code has no non-blank line fails, naming its Operation's type`() {
        assertEquals(
            Reply.error("Operation 0: 'insert_before' needs code with at least one non-blank line. Nothing written."),
            operationFailed(0, OperationFailure.BlankCode, "insert_before"),
        )
        assertEquals(
            Reply.error("Operation 1: 'insert_after' needs code with at least one non-blank line. Nothing written."),
            operationFailed(1, OperationFailure.BlankCode, "edit_in", "insert_after"),
        )
    }

    @Test
    fun `a Symbol Path without a file fails, asking for the file, when the index may not list every declaration of its name`() {
        assertEquals(
            Reply.error(
                "Operation 0: 'greet' can't be resolved without a file: so many declarations are named 'greet', dependencies' " +
                    "included, that the project index stops at a maximum number of results and may not list them all; pass its " +
                    "file. Nothing written.",
            ),
            operationFailed(0, OperationFailure.LookupFailed(LookupFailure.TooManyDeclarations("greet", "greet")), "replace"),
        )
    }

    @Test
    fun `a Symbol an earlier Operation renamed away in its one file fails, naming the file and its close names`() {
        val failure = OperationFailure.RemovedEarlier("greet", listOf("B.kt"), listOf(SymbolCandidate("greets", "B.kt")))

        assertEquals(
            Reply.error(
                "Operation 1: 'greet' in B.kt was removed or renamed by an earlier Operation of this Edit; target what that " +
                    "Operation left. Close names:\n  greets\nNothing written.",
            ),
            operationFailed(1, failure, "replace", "replace"),
        )
    }

    @Test
    fun `a Symbol Path without a file that earlier Operations renamed away in each of its files fails, naming each with its close names`() {
        val failure = OperationFailure.RemovedEarlier(
            "greet",
            listOf("B.kt", "C.kt"),
            listOf(SymbolCandidate("greets", "B.kt"), SymbolCandidate("greets", "C.kt")),
        )

        assertEquals(
            Reply.error(
                "Operation 2: 'greet' was removed or renamed by earlier Operations of this Edit in B.kt and C.kt; target what " +
                    "those Operations left. Close names:\n  greets — B.kt\n  greets — C.kt\nNothing written.",
            ),
            operationFailed(2, failure, "replace", "replace", "replace"),
        )
    }

    // kt_find_usages

    @Test
    fun `a Symbol with too many declarations to resolve project-wide fails without declaredIn`() {
        assertEquals(
            Reply.error(
                "'stay' can't be resolved without a file: so many declarations are named 'stay', dependencies' included, " +
                    "that $INDEX_STOPS and may not list them all; pass the file that declares it as declaredIn",
            ),
            Replies.findUsages(FindUsagesResult.LookupFailed(LookupFailure.TooManyDeclarations("stay", "stay"))),
        )
    }

    @Test
    fun `no usages in one file names the one file searched`() {
        assertEquals(Reply.result("No usages of stay (searched 1 file)"), Replies.findUsages(FindUsagesResult.NoUsages("stay", 1)))
    }

    @Test
    fun `no usages other than one import says so`() {
        assertEquals(
            Reply.result("No usages of wave other than 1 import (searched 3 files)"),
            Replies.findUsages(FindUsagesResult.NoUsages("wave", 3, imports = 1)),
        )
    }

    @Test
    fun `no usages other than several imports counts them`() {
        assertEquals(
            Reply.result("No usages of greet other than 2 imports (searched 3 files)"),
            Replies.findUsages(FindUsagesResult.NoUsages("greet", 3, imports = 2)),
        )
    }

    @Test
    fun `no usages other than an import in the one file searched`() {
        assertEquals(
            Reply.result("No usages of greet other than 1 import (searched 1 file)"),
            Replies.findUsages(FindUsagesResult.NoUsages("greet", 1, imports = 1)),
        )
    }

    // kt_get_definition

    private val repeated = listOf(SourceLine(4, "val n = 1 +"), SourceLine(6, "return n"))

    /** kt_get_definition's Reply when the `at` Snippet [miss] doesn't occur exactly once in greet in A.kt. */
    private fun atMissed(miss: Snippet.Miss): Reply =
        Replies.getDefinition(GetDefinitionResult.AtNotExactlyOnce("greet", "A.kt", miss), atGiven = true)

    @Test
    fun `an at snippet that starts mid-line and matches only with different indentation names the line its indents describe`() {
        assertEquals(
            Reply.error(
                "'= 1 +\n    2' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if indentation is " +
                    "ignored: the file indents line 5 by 8 spaces, 'at' by 4 spaces. Copy 'at' from kt_read_symbol output.",
            ),
            atMissed(Snippet.Miss("= 1 +\n    2", 0, emptyList(), IndentationHint(4, IndentationHint.Indents(5, "        ", "    ")))),
        )
    }

    @Test
    fun `an indented at snippet that matches only with different indentation describes the line it starts on`() {
        assertEquals(
            Reply.error(
                "'  val n = 1 +\n      2' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if " +
                    "indentation is ignored: the file indents it by 4 spaces, 'at' by 2 spaces. Copy 'at' from kt_read_symbol output.",
            ),
            atMissed(Snippet.Miss("  val n = 1 +\n      2", 0, emptyList(), IndentationHint(4, IndentationHint.Indents(4, "    ", "  ")))),
        )
    }

    @Test
    fun `a name that doesn't occur in the Symbol is named as a whole identifier`() {
        assertEquals(
            Reply.error("'m' (as a whole identifier) doesn't occur in greet in A.kt. Use kt_read_symbol to see its source."),
            Replies.getDefinition(GetDefinitionResult.NameNotExactlyOnce("m", "greet", "A.kt", emptyList()), atGiven = false),
        )
    }

    @Test
    fun `an at snippet that doesn't occur in the Symbol even with indentation ignored points at kt_read_symbol`() {
        assertEquals(
            Reply.error("'val m' doesn't occur in greet in A.kt. Use kt_read_symbol to see its source."),
            atMissed(Snippet.Miss("val m", 0, emptyList())),
        )
    }

    @Test
    fun `an at snippet whose lines are indented differently from each other says only that they differ`() {
        assertEquals(
            Reply.error(
                "'  val n = 1 +\n    2\n  return n' doesn't occur in greet in A.kt exactly as written, but occurs once at line 4 if " +
                    "indentation is ignored, where its lines are indented differently from the file's. Copy 'at' from kt_read_symbol output.",
            ),
            atMissed(Snippet.Miss("  val n = 1 +\n    2\n  return n", 0, emptyList(), IndentationHint(4, null))),
        )
    }

    @Test
    fun `a name that occurs more than once with no at snippet asks for one and lists the lines`() {
        assertEquals(
            Reply.error(
                "'n' (as a whole identifier) occurs more than once in greet in A.kt; pass an 'at' snippet that occurs exactly once:\n" +
                    "  L4: val n = 1 +\n  L6: return n",
            ),
            Replies.getDefinition(GetDefinitionResult.NameNotExactlyOnce("n", "greet", "A.kt", repeated), atGiven = false),
        )
    }

    @Test
    fun `an at snippet that occurs more than once asks for one that occurs once and lists the lines`() {
        assertEquals(
            Reply.error(
                "' n' occurs more than once in greet in A.kt; pass an 'at' snippet that occurs exactly once:\n" +
                    "  L4: val n = 1 +\n  L6: return n",
            ),
            atMissed(Snippet.Miss(" n", 2, repeated)),
        )
    }

    @Test
    fun `a name that occurs more than once in its at snippet asks for a snippet in which it occurs once`() {
        assertEquals(
            Reply.error(
                "'n' (as a whole identifier) occurs more than once in greet in A.kt; pick an 'at' snippet in which it occurs once:\n" +
                    "  L4: val n = 1 +\n  L6: return n",
            ),
            Replies.getDefinition(GetDefinitionResult.NameNotExactlyOnce("n", "greet", "A.kt", repeated), atGiven = true),
        )
    }

    @Test
    fun `a name that resolves to nothing and has no diagnostic is not an error`() {
        assertEquals(
            Reply.result("'return' on L6 of greet resolves to no declaration (a keyword, a literal or an unresolved reference)."),
            Replies.getDefinition(GetDefinitionResult.Unresolved("return", "greet", 6, null), atGiven = false),
        )
    }
}
