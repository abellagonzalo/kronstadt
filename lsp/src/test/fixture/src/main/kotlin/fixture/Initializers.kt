package fixture

/** A primary constructor, two `init` blocks and a secondary constructor with no parameters. */
class Initialized(val seed: Int) {
    var total: Int = 0

    init {
        total = seed
    }

    constructor() : this(0)

    init {
        total += 1
    }

    /** `init` blocks in a nested class, an object and a companion. */
    class Nested {
        init {
            println("nested")
        }
    }

    object Single {
        init {
            println("object")
        }
    }

    companion object {
        init {
            println("companion")
        }
    }
}

/** Shaped like kt_edit's `EditIn`: a data class whose primary constructor opens on its line, and one `init` block. */
data class Checked(
    val first: String?,
    val second: String?,
) {
    init {
        require(first != null || second != null)
    }
}
