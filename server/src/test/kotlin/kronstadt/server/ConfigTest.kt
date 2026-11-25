package kronstadt.server

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

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
        assertEquals(listOf("kotlin-lsp", "--stdio"), Config.parse(emptyArray(), env = emptyMap()).languageServer)
    }

    @Test
    fun `readiness timeout defaults to 90 seconds, overridable by environment and argument`() {
        assertEquals(90.seconds, Config.parse(emptyArray(), env = emptyMap()).readinessTimeout)
        assertEquals(120.seconds, Config.parse(emptyArray(), env = mapOf("KRONSTADT_READINESS_TIMEOUT" to "120")).readinessTimeout)
        assertEquals(
            300.seconds,
            Config.parse(arrayOf("--readiness-timeout", "300"), env = mapOf("KRONSTADT_READINESS_TIMEOUT" to "120")).readinessTimeout,
        )
    }

    @Test
    fun `rejects readiness timeouts that are not a positive number of seconds`() {
        assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--readiness-timeout", "0"), env = emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--readiness-timeout", "soon"), env = emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Config.parse(emptyArray(), env = mapOf("KRONSTADT_READINESS_TIMEOUT" to "-5")) }
    }

    @Test
    fun `validation timeout defaults to 15 seconds, overridable by environment and argument, and must be positive`() {
        assertEquals(15.seconds, Config.parse(emptyArray(), env = emptyMap()).validationTimeout)
        assertEquals(30.seconds, Config.parse(emptyArray(), env = mapOf("KRONSTADT_VALIDATION_TIMEOUT" to "30")).validationTimeout)
        assertEquals(
            45.seconds,
            Config.parse(arrayOf("--validation-timeout", "45"), env = mapOf("KRONSTADT_VALIDATION_TIMEOUT" to "30")).validationTimeout,
        )
        assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--validation-timeout", "0"), env = emptyMap()) }
    }

    @Test
    fun `collateral timeout is set by argument or environment, the argument winning`() {
        assertEquals(20.seconds, Config.parse(arrayOf("--collateral-timeout", "20"), env = emptyMap()).collateralTimeout)
        assertEquals(25.seconds, Config.parse(emptyArray(), env = mapOf("KRONSTADT_COLLATERAL_TIMEOUT" to "25")).collateralTimeout)
        assertEquals(
            40.seconds,
            Config.parse(arrayOf("--collateral-timeout", "40"), env = mapOf("KRONSTADT_COLLATERAL_TIMEOUT" to "25")).collateralTimeout,
        )
    }

    @Test
    fun `rejects collateral timeouts that are not a positive number of seconds`() {
        assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--collateral-timeout", "0"), env = emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--collateral-timeout", "later"), env = emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--collateral-timeout"), env = emptyMap()) }
        assertFailsWith<IllegalArgumentException> { Config.parse(emptyArray(), env = mapOf("KRONSTADT_COLLATERAL_TIMEOUT" to "-5")) }
    }

    @Test
    fun `collateral timeout follows the validation timeout when not set`() {
        assertEquals(15.seconds, Config.parse(emptyArray(), env = emptyMap()).collateralTimeout)
        assertEquals(45.seconds, Config.parse(arrayOf("--validation-timeout", "45"), env = emptyMap()).collateralTimeout)
        assertEquals(30.seconds, Config.parse(emptyArray(), env = mapOf("KRONSTADT_VALIDATION_TIMEOUT" to "30")).collateralTimeout)
    }

    @Test
    fun `usage documents the collateral timeout`() {
        val usage = assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--help"), env = emptyMap()) }.message.orEmpty()
        assertTrue("--collateral-timeout" in usage, usage)
        assertTrue("KRONSTADT_COLLATERAL_TIMEOUT" in usage, usage)
    }

    @Test
    fun `rejects unknown arguments`() {
        assertFailsWith<IllegalArgumentException> { Config.parse(arrayOf("--nope"), env = emptyMap()) }
    }
}
