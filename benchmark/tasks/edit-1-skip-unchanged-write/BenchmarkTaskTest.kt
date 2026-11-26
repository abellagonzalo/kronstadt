package com.squareup.kotlinpoet

import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Hidden test of the edit-1 Benchmark Task: `FileSpec.writeTo(Path)` leaves a file with the same content alone. */
class BenchmarkTaskTest {
  private val untouched = FileTime.fromMillis(0)

  @Test fun unchangedFileIsNotRewritten() {
    val dir = Files.createTempDirectory("benchmark")
    val spec = fileWith(TypeSpec.classBuilder("Foo").build())
    val path = spec.writeTo(dir)
    Files.setLastModifiedTime(path, untouched)

    assertEquals(path, spec.writeTo(dir))
    assertEquals(untouched, Files.getLastModifiedTime(path))
  }

  @Test fun changedFileIsRewritten() {
    val dir = Files.createTempDirectory("benchmark")
    val path = fileWith(TypeSpec.classBuilder("Foo").build()).writeTo(dir)
    Files.setLastModifiedTime(path, untouched)

    val changed = fileWith(TypeSpec.classBuilder("Foo").addProperty("bar", Int::class).build())
    assertEquals(path, changed.writeTo(dir))
    assertNotEquals(untouched, Files.getLastModifiedTime(path))
    assertTrue(path.readText().contains("bar"))
  }

  @Test fun missingFileIsWritten() {
    val dir = Files.createTempDirectory("benchmark").resolve("out")
    val path = fileWith(TypeSpec.classBuilder("Foo").build()).writeTo(dir)
    assertTrue(path.readText().contains("class Foo"))
  }

  private fun fileWith(type: TypeSpec): FileSpec = FileSpec.builder("com.example", "Foo").addType(type).build()
}
