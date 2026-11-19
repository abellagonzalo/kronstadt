package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SuggestionsTest {
    @Test
    fun `identical names are zero apart`() {
        assertEquals(0, Suggestions.distance("greet", "greet"))
    }

    @Test
    fun `a typo is one edit away`() {
        assertEquals(1, Suggestions.distance("greet", "greeet"))
        assertEquals(1, Suggestions.distance("greet", "gret"))
        assertEquals(1, Suggestions.distance("greet", "grest"))
    }

    @Test
    fun `against an empty name the distance is the other name's length`() {
        assertEquals(5, Suggestions.distance("", "greet"))
        assertEquals(5, Suggestions.distance("greet", ""))
    }

    @Test
    fun `distance counts the fewest insertions, deletions and substitutions`() {
        assertEquals(3, Suggestions.distance("kitten", "sitting"))
        assertEquals(3, Suggestions.distance("sitting", "kitten"))
    }

    @Test
    fun `distance is case-sensitive`() {
        assertEquals(1, Suggestions.distance("Greet", "greet"))
    }
}
