package kronstadt.core.application

import kotlinx.coroutines.test.runTest
import kronstadt.core.domain.CodeSymbol
import kronstadt.core.domain.Definition
import kronstadt.core.domain.Diagnostic
import kronstadt.core.domain.Position
import kronstadt.core.domain.Severity
import kronstadt.core.domain.SourceLocation
import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.TextRange
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Collateral Errors of an Edit that adds a direct subtype of a sealed type that existed before it: a `when` over that
 * sealed type in an untouched file can stop being exhaustive, though no existing Symbol changed its header.
 */
class SealedSubtypeCollateralTest {
    private val root = Path.of("/project")
    private val a = "src/A.kt"
    private val b = "src/B.kt"
    private val c = "src/C.kt"

    private fun range(line: Int, start: Int, end: Int) = TextRange(Position(line, start), Position(line, end))

    /** A declaration filling [line] of [text] from its indentation, its name at [column]. */
    private fun declaration(text: String, name: String, kind: SymbolKind, line: Int, column: Int, type: String? = null): CodeSymbol {
        val source = text.lines()[line]
        val indent = source.length - source.trimStart().length
        val parameters = if (kind == SymbolKind.FUNCTION) "" else null
        return CodeSymbol(name, kind, range(line, indent, source.length), range(line, column, column + name.length), parameterTypes = parameters, type = type)
    }

    private val nonExhaustive = "'when' expression must be exhaustive. Add the 'Err' branch or an 'else' branch."

    /** Uses `a.Result` in an exhaustive `when`, on line 4 (0-based). */
    private val user = "package b\n\nimport a.Result\n\nfun use(r: Result): Int = when (r) { Result.Ok -> 1 }\n"
    private val userSymbols = listOf(declaration(user, "use", SymbolKind.FUNCTION, 4, 4, "(Result): Int"))
    private val userUsesResult = SourceLocation(root.resolve(b), range(4, 11, 17))
    private val whenError = Diagnostic(range(4, 26, 30), Severity.ERROR, nonExhaustive, "NO_ELSE_IN_WHEN")
    private val userBroke = Problem(b, "use", 5, nonExhaustive, "fun use(r: Result): Int = when (r) { Result.Ok -> 1 }")

    /** A top-level `sealed interface Result` (or [modifier] interface) in A, its name at 2:(10 + modifier). */
    private fun result(modifier: String = "sealed ") = "package a\n\n${modifier}interface Result\n"

    private fun resultSymbols(modifier: String = "sealed ") =
        listOf(declaration(result(modifier), "Result", SymbolKind.INTERFACE, 2, 10 + modifier.length))

    private fun resultName(modifier: String = "sealed ") = range(2, 10 + modifier.length, 16 + modifier.length)

    private val ok = "package a\n\nobject Ok : Result\n"
    private val okAndErr = "package a\n\nobject Ok : Result\n\nobject Err : Result\n"
    private val okAndErrSymbols = listOf(
        declaration(okAndErr, "Ok", SymbolKind.OBJECT, 2, 7),
        declaration(okAndErr, "Err", SymbolKind.OBJECT, 4, 7),
    )

    /** The Edit that adds `object Err : Result` to C, next to `object Ok : Result`. */
    private val addErrToC = Replace(c, "Ok", "object Ok : Result\n\nobject Err : Result")

    /** B's `when` breaks once [file] has [text]. */
    private fun breaksWhen(file: String, text: String): (Path, String, Map<Path, String>) -> List<Diagnostic>? = { path, _, open ->
        if (path == root.resolve(b) && open[root.resolve(file)] == text) listOf(whenError) else null
    }

    @Test
    fun `adding a subtype nested in a sealed interface reports the when over it it made non-exhaustive`() = runTest {
        val base = "package a\n\nsealed interface Result {\n    object Ok : Result\n}\n"
        val current = "package a\n\nsealed interface Result {\n    object Ok : Result\n    object Err : Result\n}\n"
        fun resultSymbols(text: String, members: List<CodeSymbol>) =
            listOf(CodeSymbol("Result", SymbolKind.INTERFACE, TextRange(Position(2, 0), Position(text.lines().size - 2, 1)), range(2, 17, 23), members))
        val files = FakeFileStore(root, mapOf(a to base, b to user))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                base to resultSymbols(base, listOf(declaration(base, "Ok", SymbolKind.OBJECT, 3, 11))),
                current to resultSymbols(current, listOf(declaration(current, "Ok", SymbolKind.OBJECT, 3, 11), declaration(current, "Err", SymbolKind.OBJECT, 4, 11))),
                user to userSymbols,
            ),
            definitions = mapOf((root.resolve(a) to Position(4, 17)) to listOf(Definition.InSource(SourceLocation(root.resolve(a), range(2, 17, 23))))),
            references = mapOf((root.resolve(a) to Position(2, 17)) to listOf(userUsesResult)),
            diagnosticsInContext = breaksWhen(a, current),
        )

        val result = Edit(files, intelligence)(Replace(a, "Result", "sealed interface Result {\n    object Ok : Result\n    object Err : Result\n}"))

        assertEquals(current, files.content(a))
        assertEquals(CollateralErrors(listOf(userBroke)), assertIs<EditResult.Written>(result).collateral)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `adding a top-level subtype in another existing file reports the when over the sealed type`() = runTest {
        val files = FakeFileStore(root, mapOf(a to result(), b to user, c to ok))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                result() to resultSymbols(),
                ok to listOf(declaration(ok, "Ok", SymbolKind.OBJECT, 2, 7)),
                okAndErr to okAndErrSymbols,
                user to userSymbols,
            ),
            definitions = mapOf((root.resolve(c) to Position(4, 13)) to listOf(Definition.InSource(SourceLocation(root.resolve(a), resultName())))),
            references = mapOf((root.resolve(a) to Position(2, 17)) to listOf(userUsesResult)),
            diagnosticsInContext = breaksWhen(c, okAndErr),
        )

        val result = Edit(files, intelligence)(addErrToC)

        assertEquals(okAndErr, files.content(c))
        assertEquals(CollateralErrors(listOf(userBroke)), assertIs<EditResult.Written>(result).collateral)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `adding a subtype in a file the Edit creates reports the when over the sealed type`() = runTest {
        val created = "src/Err.kt"
        val err = "package a\n\nobject Err : Result\n"
        val files = FakeFileStore(root, mapOf(a to result(), b to user))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(result() to resultSymbols(), err to listOf(declaration(err, "Err", SymbolKind.OBJECT, 2, 7)), user to userSymbols),
            definitions = mapOf((root.resolve(created) to Position(2, 13)) to listOf(Definition.InSource(SourceLocation(root.resolve(a), resultName())))),
            references = mapOf((root.resolve(a) to Position(2, 17)) to listOf(userUsesResult)),
            diagnosticsInContext = breaksWhen(created, err),
        )

        val result = Edit(files, intelligence)(Create(created, err))

        assertEquals(err, files.content(created))
        assertEquals(CollateralErrors(listOf(userBroke)), assertIs<EditResult.Written>(result).collateral)
        intelligence.assertOneSessionClosedEverything()
    }

    @Test
    fun `adding a subtype of a type that isn't sealed checks no other file`() = runTest {
        val files = FakeFileStore(root, mapOf(a to result(modifier = ""), b to user, c to ok))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                result(modifier = "") to resultSymbols(modifier = ""),
                ok to listOf(declaration(ok, "Ok", SymbolKind.OBJECT, 2, 7)),
                okAndErr to okAndErrSymbols,
                user to userSymbols,
            ),
            definitions = mapOf(
                (root.resolve(c) to Position(4, 13)) to listOf(Definition.InSource(SourceLocation(root.resolve(a), resultName(modifier = "")))),
            ),
            references = mapOf((root.resolve(a) to Position(2, 10)) to listOf(userUsesResult)),
            diagnosticsInContext = breaksWhen(c, okAndErr),
        )

        val result = Edit(files, intelligence)(addErrToC)

        assertEquals(CollateralErrors(), assertIs<EditResult.Written>(result).collateral)
        assertEquals(emptyList(), intelligence.referenced)
        assertEquals(listOf(root.resolve(c)), intelligence.diagnosed.distinct())
    }

    @Test
    fun `adding a subtype of a sealed type the same Edit creates checks no other file`() = runTest {
        val created = "src/Fresh.kt"
        val fresh = "package a\n\nsealed interface Fresh\n\nobject Err : Fresh\n"
        val files = FakeFileStore(root, mapOf(b to user))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                fresh to listOf(declaration(fresh, "Fresh", SymbolKind.INTERFACE, 2, 17), declaration(fresh, "Err", SymbolKind.OBJECT, 4, 7)),
                user to userSymbols,
            ),
            definitions = mapOf((root.resolve(created) to Position(4, 13)) to listOf(Definition.InSource(SourceLocation(root.resolve(created), range(2, 17, 22))))),
            references = mapOf((root.resolve(created) to Position(2, 17)) to listOf(userUsesResult)),
        )

        val result = Edit(files, intelligence)(Create(created, fresh))

        assertEquals(CollateralErrors(), assertIs<EditResult.Written>(result).collateral)
        assertEquals(emptyList(), intelligence.referenced)
    }

    @Test
    fun `adding a subtype of a sealed type the same Edit adds to an existing file checks no other file`() = runTest {
        val withFresh = "package a\n\nobject Ok : Result\n\nsealed interface Fresh\n\nobject Err : Fresh\n"
        val files = FakeFileStore(root, mapOf(a to result(), b to user, c to ok))
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                result() to resultSymbols(),
                ok to listOf(declaration(ok, "Ok", SymbolKind.OBJECT, 2, 7)),
                withFresh to listOf(
                    declaration(withFresh, "Ok", SymbolKind.OBJECT, 2, 7),
                    declaration(withFresh, "Fresh", SymbolKind.INTERFACE, 4, 17),
                    declaration(withFresh, "Err", SymbolKind.OBJECT, 6, 7),
                ),
                user to userSymbols,
            ),
            definitions = mapOf((root.resolve(c) to Position(6, 13)) to listOf(Definition.InSource(SourceLocation(root.resolve(c), range(4, 17, 22))))),
            references = mapOf((root.resolve(c) to Position(4, 17)) to listOf(userUsesResult)),
        )

        val result = Edit(files, intelligence)(Replace(c, "Ok", "object Ok : Result\n\nsealed interface Fresh\n\nobject Err : Fresh"))

        assertEquals(withFresh, files.content(c))
        assertEquals(CollateralErrors(), assertIs<EditResult.Written>(result).collateral)
        assertEquals(emptyList(), intelligence.referenced)
    }

    @Test
    fun `an Edit that changes a header and adds a sealed subtype checks each file once, within one cap`() = runTest {
        val d = "src/D.kt"
        val answer = "package a\n\nfun answer(): Int = 1\n"
        val answerString = "package a\n\nfun answer(): String = \"1\"\n"
        val callers = (1..23).map { "src/C$it.kt" }
        val files = FakeFileStore(root, mapOf(a to result(), c to ok, d to answer) + callers.associateWith { user })
        val intelligence = FakeCodeIntelligence(
            symbolsByText = mapOf(
                result() to resultSymbols(),
                ok to listOf(declaration(ok, "Ok", SymbolKind.OBJECT, 2, 7)),
                okAndErr to okAndErrSymbols,
                answer to listOf(declaration(answer, "answer", SymbolKind.FUNCTION, 2, 4, "(): Int")),
                answerString to listOf(declaration(answerString, "answer", SymbolKind.FUNCTION, 2, 4, "(): String")),
                user to userSymbols,
            ),
            definitions = mapOf((root.resolve(c) to Position(4, 13)) to listOf(Definition.InSource(SourceLocation(root.resolve(a), resultName())))),
            references = mapOf(
                (root.resolve(d) to Position(2, 4)) to callers.take(15).map { SourceLocation(root.resolve(it), range(4, 0, 1)) },
                (root.resolve(a) to Position(2, 17)) to callers.drop(9).map { SourceLocation(root.resolve(it), range(4, 11, 17)) },
            ),
        )

        val result = Edit(files, intelligence)(listOf(Replace(d, "answer", "fun answer(): String = \"1\""), addErrToC))

        assertEquals(3, assertIs<EditResult.Written>(result).collateral.filesUnchecked)
        val pulls = intelligence.diagnosed.filterNot { it == root.resolve(c) || it == root.resolve(d) }.groupingBy { it }.eachCount()
        assertEquals(callers.take(20).associate { root.resolve(it) to 2 }, pulls)
    }
}
