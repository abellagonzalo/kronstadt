package kronstadt.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfigTest {
    @Test
    fun `parses explicit arguments`() {
        val config = Config.parse(arrayOf("--project-root", "/tmp/p", "--language-server", "kotlin-lsp --stdio"), env = emptyMap())
        assertEquals(Path.of("/tmp/p"), config.projectRoot)
        assertEquals(listOf("kotlin-lsp", "--stdio"), config.languageServer)
    }

    @Test
    fun `falls back to the environment, then the default command`() {
        assertEquals(listOf("my-ls"), Config.parse(emptyArray(), env = mapOf("KRONSTADT_LANGUAGE_SERVER" to "my-ls")).languageServer)
        assertEquals(listOf("kotlin-language-server"), Config.parse(emptyArray(), env = emptyMap()).languageServer)
    }

    @Test
    fun `rejects unknown arguments`() {
        assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--nope"), env = emptyMap()) }
    }
}
