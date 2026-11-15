package kronstadt.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** A Kotlin file's package comes only from the `package` directive in its header. */
class PackageHeaderTest {
    private fun packageOf(text: String) = TextDocument(text).packageName

    @Test
    fun `reads the package directive of a plain header`() {
        assertEquals("com.example.a", packageOf("package com.example.a\n\nclass Config\n"))
    }

    @Test
    fun `skips line, block, nested block and KDoc comments before the directive`() {
        assertEquals("a.b", packageOf("// package x.y\npackage a.b\n"))
        assertEquals("a.b", packageOf("/*\n package x.y\n */\npackage a.b\n"))
        assertEquals("a.b", packageOf("/* outer /* inner */\npackage x.y\n*/\npackage a.b\n"))
        assertEquals("a.b", packageOf("/**\n * package x.y\n */\npackage a.b\n"))
    }

    @Test
    fun `skips a shebang line`() {
        assertEquals("a.b", packageOf("#!/usr/bin/env kotlin\npackage a.b\n"))
    }

    @Test
    fun `skips file annotations, with arguments or in a bracketed list`() {
        assertEquals("a.b", packageOf("@file:JvmName(\"Util\")\npackage a.b\n"))
        assertEquals("a.b", packageOf("@file:Suppress(\"package x.y\", \"UNUSED\")\n@file:JvmMultifileClass\npackage a.b\n"))
        assertEquals("a.b", packageOf("@file:[JvmName(\"A\") Suppress(\"B\")]\npackage a.b\n"))
        assertEquals("a.b", packageOf("@file : kotlin.jvm.JvmName(\"A\") package a.b\n"))
        assertEquals("a.b", packageOf("@file:Suppress(/* ) */ \"X\" // )\n)\npackage a.b\n"))
    }

    @Test
    fun `skips a byte order mark`() {
        assertEquals("a.b", packageOf("﻿package a.b\n"))
    }

    @Test
    fun `unquotes backticked segments`() {
        assertEquals("my pkg.a", packageOf("package `my pkg`.a\n"))
    }

    @Test
    fun `stops at a trailing semicolon`() {
        assertEquals("a.b", packageOf("package a.b;\nimport c.D\n"))
    }

    @Test
    fun `reads a directive split across whitespace and comments`() {
        assertEquals("a.b.c", packageOf("package a . /* x */ b\n    .c\n\nclass X\n"))
    }

    @Test
    fun `a file whose header starts with an import or a declaration is in the default package`() {
        assertEquals("", packageOf("import a.b.C\n\npackage x.y\n"))
        assertEquals("", packageOf("class X\n\npackage x.y\n"))
        assertEquals("", packageOf("@Suppress(\"X\")\nclass X\n"))
        assertEquals("", packageOf("val packaged = 1\n"))
    }

    @Test
    fun `an empty file is in the default package`() {
        assertEquals("", packageOf(""))
        assertEquals("", packageOf("// only a comment\n"))
    }

    @Test
    fun `a body line starting with package in a file without a header doesn't count`() {
        val source = "fun template() = \"\"\"\npackage other\n\"\"\"\n"
        assertEquals("", packageOf(source))
    }
}
