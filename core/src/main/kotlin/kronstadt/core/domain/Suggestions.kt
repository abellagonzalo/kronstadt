package kronstadt.core.domain

/** Close names offered when a Symbol Path resolves to nothing: how many at most, and how close a name is. */
internal object Suggestions {
    /** The most suggestions a failed lookup offers. */
    const val MAX: Int = 5

    /** Levenshtein distance: the fewest single-character insertions, deletions and substitutions from [a] to [b]. */
    fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            previous = current
        }
        return previous[b.length]
    }
}
