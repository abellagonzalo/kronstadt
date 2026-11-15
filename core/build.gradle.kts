// Pure domain + application layer. Must not depend on lsp4j, the MCP SDK, or any I/O library.
plugins {
    id("kronstadt.kotlin-conventions")
}

dependencies {
    // Coroutines aren't I/O: the write lock and the Validation timeout need them.
    implementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
}
