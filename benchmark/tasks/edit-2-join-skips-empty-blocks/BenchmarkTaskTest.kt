package com.squareup.kotlinpoet

import kotlin.test.Test
import kotlin.test.assertEquals

/** Hidden test of the edit-2 Benchmark Task: `joinToCode` leaves out empty code blocks. */
class BenchmarkTaskTest {
  private val empty = CodeBlock.builder().build()

  @Test fun emptyBlocksAreLeftOut() {
    val blocks = listOf(empty, CodeBlock.of("a"), empty, CodeBlock.of("b"), empty)
    assertEquals("a, b", blocks.joinToCode().toString())
  }

  @Test fun prefixAndSuffixStay() {
    assertEquals("(a)", listOf(CodeBlock.of("a"), empty).joinToCode(prefix = "(", suffix = ")").toString())
    assertEquals("()", listOf(empty, empty).joinToCode(prefix = "(", suffix = ")").toString())
  }

  @Test fun transformedEmptyBlocksAreLeftOut() {
    val joined = listOf("x", "", "y").joinToCode(" + ") { if (it.isEmpty()) empty else CodeBlock.of("%S", it) }
    assertEquals("\"x\" + \"y\"", joined.toString())
  }

  @Test fun nonEmptyBlocksAreJoinedAsBefore() {
    assertEquals("a; b; c", listOf("a", "b", "c").map { CodeBlock.of(it) }.joinToCode("; ").toString())
  }
}
