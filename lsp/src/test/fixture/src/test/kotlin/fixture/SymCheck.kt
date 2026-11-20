package fixture

import fixture.gamma.Sym
import java.io.File

/** Two Usages of Sym's `signature`, an import of Sym itself, and `java.io.File`, which was already unused. */
fun checkSym(): Int {
    val s = Sym(name = "a", signature = listOf("x"))
    return s.signature.size
}
