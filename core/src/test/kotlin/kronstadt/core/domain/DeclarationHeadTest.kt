package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class DeclarationHeadTest {
    /** [text], a whole declaration, as a Symbol whose name is [name] after its `class`, `interface` or `object`. */
    private fun declared(text: String, name: String): Pair<TextDocument, CodeSymbol> {
        val document = TextDocument(text)
        val start = Regex("""\b(class|interface|object) $name\b""").find(text)!!.range.last - name.length + 1
        val symbol = CodeSymbol(
            name,
            SymbolKind.CLASS,
            TextRange(Position(0, 0), document.positionOf(text.length)),
            TextRange(document.positionOf(start), document.positionOf(start + name.length)),
        )
        return document to symbol
    }

    /** The supertype names [DeclarationHead.supertypes] finds in [text], declaring [name]. */
    private fun supertypes(text: String, name: String = "Foo"): List<String> {
        val (document, symbol) = declared(text, name)
        return DeclarationHead.supertypes(document, symbol).map { position ->
            val start = document.offsetOf(position)
            text.substring(start).takeWhile(Character::isJavaIdentifierPart)
        }
    }

    @Test
    fun `supertypes are the names after the colon, without type arguments or constructor calls`() {
        assertEquals(
            listOf("Base", "Comparable", "Bar"),
            supertypes("class Foo<T : Bound>(val x: Map<String, Int> = mapOf()) : Base(x, \"a, b\"), Comparable<Foo<T>>, Bar {\n    val y: Int = 1\n}"),
        )
    }

    @Test
    fun `a qualified supertype is found at its last segment, and an annotation on it is skipped`() {
        assertEquals(listOf("Base", "Other"), supertypes("object Foo : @Ann(\"x\") a.b.Base(), Other"))
    }

    @Test
    fun `what a delegating by names isn't a supertype, and a where clause ends the list`() {
        assertEquals(listOf("Bar", "Baz"), supertypes("class Foo<T>(b: Bar) : Bar by b, Baz where T : Qux"))
    }

    @Test
    fun `a declaration without supertypes has none, whatever its constructor's types`() {
        assertEquals(emptyList(), supertypes("class Foo(val x: Int) {\n    val y: Base = Base()\n}"))
        assertEquals(emptyList(), supertypes("class Foo @Inject constructor(val x: Int)"))
        assertEquals(listOf("Bar"), supertypes("class Foo : (Int) -> Unit, Bar"))
    }

    @Test
    fun `a declaration is sealed by its modifiers, not by its KDoc or annotations`() {
        fun isSealed(text: String) = declared(text, "Foo").let { (document, symbol) -> DeclarationHead.isSealed(document, symbol) }

        assertEquals(true, isSealed("/** A Foo. */\n@Ann\npublic sealed interface Foo"))
        assertEquals(false, isSealed("/** Not sealed. */\n@Ann(\"sealed\")\nabstract class Foo"))
        assertEquals(false, isSealed("// sealed\ninterface Foo"))
    }
}
