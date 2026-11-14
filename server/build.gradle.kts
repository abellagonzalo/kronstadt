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
}

application {
    applicationName = "kronstadt"
    mainClass = "kronstadt.server.MainKt"
}
