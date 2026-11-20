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

/** Extension receivers declared as members. */
class Formatter {
    fun String.decorate(): String = "*$this*"

    fun Int.decorate(): String = "#$this"

    fun decorate(): String = "plain"
}

/** A property and a function sharing a name. */
class Sized {
    val size: Int = 0

    fun size(extra: Int): Int = size + extra
}

/** A primary constructor with a property parameter and a plain parameter, and a secondary constructor. */
class Box(val width: Int, height: Int) {
    val area: Int = width * height

    constructor(side: Int) : this(side, side)
}

/** An unnamed companion. */
class Widget {
    companion object {
        fun create(): Widget = Widget()
    }
}

/** An enum with a member inside an entry body. */
enum class Tint(val code: Int) {
    PALE(1),
    DEEP(2) {
        fun hex(): String = "deep"
    },
}

typealias Names = List<String>

/** Accessors, local declarations and lambdas. */
class Hidden {
    var counter: Int = 0
        get() = field
        set(value) {
            field = value
        }

    fun outer(): Int {
        fun local(): Int = 1
        class LocalClass
        val lambda = { x: Int -> x + 1 }
        return lambda(local())
    }
}

fun <T> List<T>.second(): T = this[1]

val String.firstChar: Char
    get() = this[0]

/** A multi-line primary constructor whose property parameters carry trailing commas. */
class Crate(
    val length: Int,
    /** Front to back. */
    val depth: Int, // deep
)
