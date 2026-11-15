package fixture

typealias Area = Double

/** Overloads, a property and a function sharing a name, accessors, constructors and local functions. */
class Calculator(val name: String) {
    constructor(name: String, scale: Int) : this(name) {
        this.scale = scale
    }

    var scale: Int = 1
        get() = field
        set(value) {
            field = value.coerceAtLeast(1)
        }

    val total: Int = 0

    fun total(): Int = total * scale

    fun parse(text: String): Int = text.toInt() * scale

    fun parse(text: String, radix: Int): Int = text.toInt(radix) * scale

    fun sumOfSquares(values: List<Int>): Int {
        fun square(x: Int): Int = x * x
        return values.sumOf { square(it) }
    }

    companion object {
        const val DEFAULT_SCALE: Int = 1
    }
}

/** Nested, inner, and a named companion. */
class Outer {
    private val secret = 42

    class Nested {
        fun describe(): String = "nested"
    }

    inner class Inner {
        fun reveal(): Int = secret
    }

    companion object Factory {
        fun create(): Outer = Outer()
    }
}

/** Enum with bodies. */
enum class Operation {
    PLUS {
        override fun apply(a: Int, b: Int): Int = a + b
    },
    TIMES {
        override fun apply(a: Int, b: Int): Int = a * b
    };

    abstract fun apply(a: Int, b: Int): Int
}

/** Extensions. */
fun String.shout(): String = uppercase() + "!"

val String.lastChar: Char
    get() = this[length - 1]

fun area(width: Double, height: Double): Area = width * height
