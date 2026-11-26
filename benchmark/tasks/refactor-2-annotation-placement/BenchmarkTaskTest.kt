package com.squareup.kotlinpoet

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Hidden test of the refactor-2 Benchmark Task: `CodeWriter.emitAnnotations` takes an `AnnotationPlacement`. */
class BenchmarkTaskTest {
  private val annotations = listOf(
    AnnotationSpec.builder(ClassName("com.example", "A")).build(),
    AnnotationSpec.builder(ClassName("com.example", "B")).build(),
  )

  @Test fun inlinePlacement() {
    assertEquals(
      "@com.example.A @com.example.B ",
      buildCodeString { emitAnnotations(annotations, AnnotationPlacement.INLINE) },
    )
  }

  @Test fun ownLinePlacement() {
    assertEquals(
      "@com.example.A\n@com.example.B\n",
      buildCodeString { emitAnnotations(annotations, AnnotationPlacement.OWN_LINE) },
    )
  }

  @Test fun placementHasExactlyTheTwoEntries() {
    assertEquals(listOf("INLINE", "OWN_LINE"), AnnotationPlacement.entries.map { it.name })
  }

  @Test fun booleanOverloadIsGone() {
    val booleanOverloads = Class.forName("com.squareup.kotlinpoet.CodeWriter").declaredMethods.filter {
      it.name == "emitAnnotations" && it.parameterTypes.any { type -> type == Boolean::class.javaPrimitiveType }
    }
    assertTrue(booleanOverloads.isEmpty(), "emitAnnotations still takes a Boolean: $booleanOverloads")
  }
}
