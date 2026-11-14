// Pure domain + application layer. Must not depend on lsp4j, the MCP SDK, or any I/O library.
plugins {
    id("kronstadt.kotlin-conventions")
}

dependencies {
    testImplementation(libs.coroutines.test)
}
