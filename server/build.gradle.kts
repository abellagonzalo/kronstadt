// MCP stdio server: wires adapters into use cases and exposes them as tools.
plugins {
    id("kronstadt.kotlin-conventions")
    application
}

dependencies {
    implementation(project(":core"))
    implementation(project(":lsp"))
    implementation(project(":workspace"))
    implementation(libs.mcp.server)
    implementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
}

application {
    applicationName = "kronstadt"
    mainClass = "kronstadt.server.MainKt"
    // Index seeding calls clonefile(2) through the Foreign Function API.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}
