package kronstadt.lsp

import kronstadt.core.domain.SymbolKind
import kronstadt.core.domain.SymbolSearch
import kronstadt.core.domain.TextDocument
import org.eclipse.lsp4j.DocumentSymbol
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SymbolInformation
import org.eclipse.lsp4j.WorkspaceSymbol
import org.eclipse.lsp4j.WorkspaceSymbolLocation
import org.eclipse.lsp4j.jsonrpc.messages.Either
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.eclipse.lsp4j.SymbolKind as LspSymbolKind

class LspMappingTest {
    private fun range(a: Int, b: Int) = Range(Position(a, 0), Position(b, 1))

    @Test
    fun `maps hierarchical document symbols with children`() {
        val method = DocumentSymbol("find", LspSymbolKind.Method, range(2, 4), range(2, 2), "(String): Repo?")
        val clazz = DocumentSymbol("Repo", LspSymbolKind.Class, range(0, 5), range(0, 0), null, listOf(method))
        val result = LspMapping.documentSymbols(listOf(Either.forRight(clazz)), TextDocument(""))

        val mapped = result.single()
        assertEquals(SymbolKind.CLASS, mapped.kind)
        assertEquals(SymbolKind.FUNCTION, mapped.children.single().kind)
        assertEquals(2, mapped.children.single().range.start.line)
        assertEquals("(String): Repo?", mapped.children.single().type)
    }

    @Test
    fun `init blocks are dropped at any depth, constructors with no parameters kept`() {
        fun init(line: Int) = DocumentSymbol("<class initializer>", LspSymbolKind.Constructor, range(line, line + 1), range(line, line + 1), null)
        val noArgs = DocumentSymbol("Box", LspSymbolKind.Constructor, range(2, 2), range(2, 2), "()")
        val nested = DocumentSymbol("Inner", LspSymbolKind.Class, range(5, 8), range(5, 5), null, listOf(init(6)))
        val clazz = DocumentSymbol("Box", LspSymbolKind.Class, range(0, 9), range(0, 0), null, listOf(init(1), noArgs, init(3), nested))

        val mapped = LspMapping.documentSymbols(listOf(Either.forRight(clazz)), TextDocument("")).single()

        assertEquals(listOf("Box" to SymbolKind.CONSTRUCTOR, "Inner" to SymbolKind.CLASS), mapped.children.map { it.name to it.kind })
        assertEquals("", mapped.children.first().parameterTypes)
        assertEquals(emptyList(), mapped.children.last().children)
    }

    /** The receiver, Signature and type mapped from a kotlin-lsp `documentSymbol` of [kind] whose `detail` is [detail]. */
    private fun parts(kind: LspSymbolKind, detail: String?) =
        LspMapping.documentSymbol(DocumentSymbol("s", kind, range(0, 1), range(0, 0), detail), TextDocument(""))
            .let { listOf(it.receiver, it.parameterTypes, it.type) }

    @Test
    fun `a function's Signature is the text inside its parameter parentheses, and its type the whole detail`() {
        assertEquals(listOf(null, "String, Int", "(String, Int): Int"), parts(LspSymbolKind.Function, "(String, Int): Int"))
        assertEquals(listOf(null, "", "(): Int"), parts(LspSymbolKind.Method, "(): Int"))
        assertEquals(listOf(null, "List<String>, vararg Int", "(List<String>, vararg Int): Unit"), parts(LspSymbolKind.Function, "(List<String>, vararg Int): Unit"))
    }

    @Test
    fun `parentheses nested in parameter types or a function-type receiver stay inside them`() {
        assertEquals(listOf(null, "T, (T) -> String", "(T, (T) -> String): String"), parts(LspSymbolKind.Function, "(T, (T) -> String): String"))
        assertEquals(listOf("((Int) -> Unit)", "Int", "(Int): Unit"), parts(LspSymbolKind.Function, "((Int) -> Unit).(Int): Unit"))
    }

    @Test
    fun `an extension function's receiver comes before its parameter list, and its type leaves it out`() {
        assertEquals(listOf("String", "Int", "(Int): Unit"), parts(LspSymbolKind.Function, "String.(Int): Unit"))
        assertEquals(listOf("List<T>", "", "(): T"), parts(LspSymbolKind.Function, "List<T>.(): T"))
    }

    @Test
    fun `a constructor's detail is only its parameter list`() {
        assertEquals(listOf(null, "String", "(String)"), parts(LspSymbolKind.Constructor, "(String)"))
    }

    @Test
    fun `a property has no Signature, and an extension property's receiver comes before its type`() {
        assertEquals(listOf(null, null, ": Int"), parts(LspSymbolKind.Property, ": Int"))
        assertEquals(listOf("String", null, ": Char"), parts(LspSymbolKind.Property, "String.: Char"))
        assertEquals(listOf(null, null, ": Map<String, Int>"), parts(LspSymbolKind.Field, ": Map<String, Int>"))
    }

    @Test
    fun `no detail maps to nothing, and a detail that can't be parsed is kept whole as the type`() {
        assertEquals(listOf(null, null, null), parts(LspSymbolKind.Function, null))
        assertEquals(listOf(null, null, "String -> Int"), parts(LspSymbolKind.Function, "String -> Int"))
        assertEquals(listOf(null, null, "Char"), parts(LspSymbolKind.Property, "Char"))
        assertEquals(listOf(null, null, "(String)"), parts(LspSymbolKind.Class, "(String)"))
    }

    /** The kind mapped from a kotlin-lsp `documentSymbol` of [kind] for [declaration], named [name], inside [text]. */
    private fun mappedKind(kind: LspSymbolKind, text: String, declaration: String, name: String): SymbolKind {
        val document = TextDocument(text)
        fun at(offset: Int) = document.positionOf(offset).let { Position(it.line, it.character) }
        val start = text.indexOf(declaration).also { check(it >= 0) { declaration } }
        val nameStart = text.indexOf(name, start).also { check(it >= 0) { name } }
        val range = Range(at(start), at(start + declaration.length))
        val selection = Range(at(nameStart), at(nameStart + name.length))
        return LspMapping.documentSymbol(DocumentSymbol(name, kind, range, selection, ": Int"), document).kind
    }

    @Test
    fun `a property is val or var as its declaration says, whatever kind kotlin-lsp reports`() {
        val text = """
            class Box(private val width: Int, var height: Int) {
                /** Says var, but is a val. */
                @Volatile val area: Int = 0

                internal var `var`: Int = 0

                val <T> List<T>.second: T get() = this[1]
            }
        """.trimIndent()
        // kotlin-lsp reports a constructor property as Variable and any other property as Property, val or var alike.
        assertEquals(SymbolKind.PROPERTY, mappedKind(LspSymbolKind.Variable, text, "private val width: Int", "width"))
        assertEquals(SymbolKind.VARIABLE, mappedKind(LspSymbolKind.Variable, text, "var height: Int", "height"))
        assertEquals(SymbolKind.PROPERTY, mappedKind(LspSymbolKind.Property, text, "/** Says var, but is a val. */\n    @Volatile val area: Int = 0", "area"))
        assertEquals(SymbolKind.VARIABLE, mappedKind(LspSymbolKind.Property, text, "internal var `var`: Int = 0", "`var`"))
        assertEquals(SymbolKind.PROPERTY, mappedKind(LspSymbolKind.Property, text, "val <T> List<T>.second: T get() = this[1]", "second"))
    }

    @Test
    fun `a property whose declaration shows neither val nor var keeps the kind kotlin-lsp reports`() {
        val text = "class Box(width: Int)\n"
        assertEquals(SymbolKind.VARIABLE, mappedKind(LspSymbolKind.Variable, text, "width: Int", "width"))
        assertEquals(SymbolKind.PROPERTY, mappedKind(LspSymbolKind.Field, text, "width: Int", "width"))
    }

    @Test
    fun `falls back gracefully for flat symbol information`() {
        val info = SymbolInformation("Repo", LspSymbolKind.Interface, Location("file:///p/Repo.kt", range(0, 3)))
        val mapped = LspMapping.documentSymbols(listOf(Either.forLeft(info)), TextDocument("")).single()
        assertEquals(SymbolKind.INTERFACE, mapped.kind)
        assertEquals(mapped.range, mapped.selectionRange)
    }

    @Test
    fun `keeps only local workspace symbols that carry a range`() {
        val local = WorkspaceSymbol("Repo", LspSymbolKind.Class, Either.forLeft(Location("file:///p/Repo.kt", range(1, 2))))
        val inJar = WorkspaceSymbol("List", LspSymbolKind.Interface, Either.forLeft(Location("jar:file:///lib.jar!/List.class", range(0, 0))))
        val unresolved = WorkspaceSymbol("Lazy", LspSymbolKind.Class, Either.forRight(WorkspaceSymbolLocation("file:///p/Lazy.kt")))

        val mapped = LspMapping.workspaceSymbols(Either.forRight(listOf(local, inJar, unresolved)))
        assertEquals(listOf("Repo"), mapped.map { it.name })
        assertEquals(Path.of("/p/Repo.kt"), mapped.single().file)
    }

    @Test
    fun `a full page of workspace symbols may be incomplete, for exact names only when it ends in one`() {
        fun page(vararg names: String, padding: Int = 0): Either<List<SymbolInformation>, List<WorkspaceSymbol>> {
            val inJar = Location("jar:file:///lib.jar!/A.class", range(0, 0))
            return Either.forRight((names.toList() + List(padding) { "getOther$it" }).map { WorkspaceSymbol(it, LspSymbolKind.Function, Either.forLeft(inJar)) })
        }
        val cap = LspMapping.WORKSPACE_SYMBOL_CAP

        assertEquals(SymbolSearch.Completeness.COMPLETE, LspMapping.workspaceSymbolCompleteness(page("get", padding = cap - 2), "get"))
        assertEquals(SymbolSearch.Completeness.COMPLETE, LspMapping.workspaceSymbolCompleteness(null, "get"))
        // Full, dependency symbols counted too: names merely containing the query fill the end, so every exact one came.
        assertEquals(SymbolSearch.Completeness.EXACT_NAMES_COMPLETE, LspMapping.workspaceSymbolCompleteness(page("get", padding = cap - 1), "get"))
        assertEquals(SymbolSearch.Completeness.INCOMPLETE, LspMapping.workspaceSymbolCompleteness(page(*Array(cap) { "get" }), "get"))
        // A page ending in the name in another case may have cut the exact name off too.
        assertEquals(SymbolSearch.Completeness.INCOMPLETE, LspMapping.workspaceSymbolCompleteness(page("get", padding = cap - 2).let { Either.forRight(it.right + WorkspaceSymbol("Get", LspSymbolKind.Class, it.right.first().location)) }, "get"))
    }

    // Definition URIs as kotlin-lsp 271.2085.0 returns them (see the contract test).

    @Test
    fun `a definition in a local file is local`() {
        assertEquals(DefinitionTarget.Local(Path.of("/p/src/A.kt")), LspMapping.definitionTarget("file:///p/src/A.kt"))
    }

    @Test
    fun `a definition in a sources jar names the jar and the entry inside it`() {
        assertEquals(
            DefinitionTarget.Jar(Path.of("/cache/kotlin-stdlib-2.4.21-sources.jar"), "commonMain/kotlin/collections/Collections.kt"),
            LspMapping.definitionTarget("jar:///cache/kotlin-stdlib-2.4.21-sources.jar!/commonMain/kotlin/collections/Collections.kt"),
        )
    }

    @Test
    fun `a definition in the JDK names its module and the class file`() {
        assertEquals(
            DefinitionTarget.Jdk("java.base", "java/io/File.class"),
            LspMapping.definitionTarget("jrt:///opt/jbr/Contents/Home!/java.base/java/io/File.class"),
        )
    }

    @Test
    fun `a definition in the JDK's src zip is a JDK target, with its module and the source entry`() {
        assertEquals(
            DefinitionTarget.Jdk("java.base", "java/io/File.java", sources = Path.of("/opt/temurin-25/lib/src.zip")),
            LspMapping.definitionTarget("jar:///opt/temurin-25/lib/src.zip!/java.base/java/io/File.java"),
        )
    }

    @Test
    fun `an archive named src zip without a module first is an ordinary jar`() {
        assertEquals(
            DefinitionTarget.Jar(Path.of("/libs/src.zip"), "java/io/File.java"),
            LspMapping.definitionTarget("jar:///libs/src.zip!/java/io/File.java"),
        )
    }

    @Test
    fun `definition URIs are decoded, and other schemes are dropped`() {
        assertEquals(
            DefinitionTarget.Jar(Path.of("/my cache/lib-sources.jar"), "a/B C.kt"),
            LspMapping.definitionTarget("jar:///my%20cache/lib-sources.jar!/a/B%20C.kt"),
        )
        assertEquals(null, LspMapping.definitionTarget("untitled:Scratch.kt"))
    }
}
