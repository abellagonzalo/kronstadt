package kronstadt.server

/**
 * A failure whose [message] Kronstadt wrote for the agent: what went wrong and what to do next. A tool call that
 * throws it fails with the message alone, without the exception's class name.
 */
internal class AgentFacingFailure(override val message: String) : Exception(message)
