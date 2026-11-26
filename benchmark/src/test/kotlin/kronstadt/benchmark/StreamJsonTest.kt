package kronstadt.benchmark

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamJsonTest {
    private val init = """{"type":"system","subtype":"init","session_id":"s","tools":["Read"],"mcp_servers":[]}"""

    private fun assistant(id: String, input: Int, output: Int, cacheRead: Int = 0, cacheWrite: Int = 0) =
        """{"type":"assistant","message":{"id":"$id","role":"assistant","content":[{"type":"text","text":"hi"}],""" +
            """"usage":{"input_tokens":$input,"output_tokens":$output,"cache_read_input_tokens":$cacheRead,"cache_creation_input_tokens":$cacheWrite}}}"""

    @Test
    fun `the result event gives the reply, the cost and the tokens of every model the session used`() {
        val result = """{"type":"result","subtype":"success","is_error":false,"num_turns":4,"result":"Done.","total_cost_usd":0.25,""" +
            """"usage":{"input_tokens":10,"output_tokens":20,"cache_read_input_tokens":300,"cache_creation_input_tokens":40},""" +
            """"modelUsage":{"claude-sonnet-5-5":{"inputTokens":10,"outputTokens":20,"cacheReadInputTokens":300,"cacheCreationInputTokens":40,"costUSD":0.2},""" +
            """"claude-haiku-5-5":{"inputTokens":1,"outputTokens":2,"cacheReadInputTokens":3,"cacheCreationInputTokens":4,"costUSD":0.05}}}"""

        val output = StreamJson.parse(sequenceOf(init, assistant("m1", 10, 20, 300, 40), "", result))

        assertEquals(SessionResult(subtype = "success", isError = false, reply = "Done.", costUsd = 0.25), output.result)
        assertEquals(Tokens(input = 11, output = 22, cacheRead = 303, cacheWrite = 44), output.tokens)
        assertEquals(380, Tokens(10, 20, 300, 50).total)
    }

    @Test
    fun `without modelUsage the result's own usage counts`() {
        val result = """{"type":"result","subtype":"success","is_error":false,"result":"ok","total_cost_usd":0.1,""" +
            """"usage":{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":3,"cache_creation_input_tokens":4}}"""

        assertEquals(Tokens(1, 2, 3, 4), StreamJson.parse(sequenceOf(result)).tokens)
    }

    @Test
    fun `a session that hit the turn cap has a result without a reply`() {
        val result = """{"type":"result","subtype":"error_max_turns","is_error":false,"num_turns":51,"total_cost_usd":1.5,""" +
            """"usage":{"input_tokens":1,"output_tokens":2,"cache_read_input_tokens":3,"cache_creation_input_tokens":4}}"""

        assertEquals(SessionResult("error_max_turns", isError = false, reply = null, costUsd = 1.5), StreamJson.parse(sequenceOf(result)).result)
    }

    @Test
    fun `a session killed before its result counts the tokens of the messages it got, each message once, and no cost`() {
        val output = StreamJson.parse(
            sequenceOf(
                init,
                assistant("m1", 5, 1, 100, 10),
                assistant("m1", 5, 7, 100, 10), // the same message streamed again, with its final usage
                """{"type":"user","message":{"role":"user","content":[]}}""",
                assistant("m2", 3, 2),
                """{"type":"assistant","message":{"id":"m3","content":[]""", // cut off by the kill
            ),
        )

        assertNull(output.result)
        assertEquals(Tokens(input = 8, output = 9, cacheRead = 100, cacheWrite = 10), output.tokens)
    }
}
