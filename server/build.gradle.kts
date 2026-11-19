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

// Seam 2: contract tests that run the server against a real kotlin-lsp (must be on PATH, or set
// KRONSTADT_LANGUAGE_SERVER). Slow, so `test` skips them; run with `./gradlew :server:contractTest`.
tasks.test {
    useJUnitPlatform { excludeTags("contract") }
}

val contractTest by tasks.registering(Test::class) {
    description = "Runs the server contract tests against a real kotlin-lsp."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("contract") }
    val fixture = rootProject.layout.projectDirectory.dir("lsp/src/test/fixture")
    systemProperty("kronstadt.fixture", fixture.asFile.absolutePath)
    inputs.dir(fixture)
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed", "skipped") }
}
