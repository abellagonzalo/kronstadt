package com.squareup.kotlinpoet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hidden test of the refactor-1 Benchmark Task: `ClassName.canonicalName` is now `fqName`, and nothing else was renamed. */
class BenchmarkTaskTest {
  @Test fun classNameHasFqName() {
    assertEquals("com.example.Outer.Inner", ClassName("com.example", "Outer", "Inner").fqName)
  }

  @Test fun classNameNoLongerHasCanonicalName() {
    assertTrue(ClassName::class.java.methods.none { it.name == "getCanonicalName" })
  }

  @Test fun memberNameKeepsCanonicalName() {
    assertEquals("com.example.Outer.member", MemberName(ClassName("com.example", "Outer"), "member").canonicalName)
  }
}
