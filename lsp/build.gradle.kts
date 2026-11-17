// Adapter: implements the CodeIntelligence port by talking to a Kotlin language server over LSP.
plugins {
    id("kronstadt.kotlin-conventions")
}

dependencies {
    api(project(":core"))
    implementation(libs.lsp4j)
    implementation(libs.coroutines.core)
    testImplementation(libs.coroutines.test)
    // The Write-back acceptance test writes through the real FileStore; the Index Seed one seeds real index directories.
    testImplementation(project(":workspace"))
}

// Seam 2: contract tests against a real kotlin-lsp (must be on PATH, or set KRONSTADT_LANGUAGE_SERVER).
// Slow, so `test` skips them; run with `./gradlew :lsp:contractTest`.
tasks.test {
    useJUnitPlatform { excludeTags("contract") }
}

val contractTest by tasks.registering(Test::class) {
    description = "Runs the adapter contract tests against a real kotlin-lsp."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("contract") }
    systemProperty("kronstadt.fixture", layout.projectDirectory.dir("src/test/fixture").asFile.absolutePath)
    inputs.dir("src/test/fixture")
    outputs.upToDateWhen { false }
    testLogging { events("passed", "failed", "skipped") }
}
