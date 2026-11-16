package fixture

import java.io.File

/** Names that resolve to project, local, library and JDK declarations, for kt_get_definition. */
class Definitions {
    fun uses(radius: Int): String {
        val documented = Documented()
        val words = listOf("a", "b")
        val file = File("x")
        return "$radius ${documented.blocky()} ${"hi".shout()} $words ${file.name} ${"7".toInt()} ${area(1.0, 2.0)}"
    }
}
