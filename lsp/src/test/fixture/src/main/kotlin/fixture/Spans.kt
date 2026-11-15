package fixture

/**
 * KDoc above an annotated class.
 */
@Suppress("unused")
@Deprecated("probe")
internal class Documented {
    // A line comment directly above.
    // Two of them.
    @JvmField
    val annotated: Int = 1 // trailing comment

    /* A block comment above. */
    fun blocky(): Int = 2 /* trailing block */

    fun generic(items: List<Map<String, Int>>, vararg more: String): Unit = Unit

    fun <T> wrap(value: T, block: (T) -> String): String = block(value)
}

enum class Color(val hex: String) {
    /** Entry KDoc. */
    RED("f00"),
    GREEN("0f0") {
        fun shade(): String = "dark"
    },
    BLUE("00f");

    fun describe(): String = name
}

fun `backticked name`(): Int = 3
