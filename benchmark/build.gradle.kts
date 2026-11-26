// Dev-only benchmark (never shipped): runs Claude Code on fixed Benchmark Tasks with and without Kronstadt, and reports
// tokens, cost and wall-clock time. On demand only: `./gradlew :benchmark:run --args="--level medium"`. Not wired into
// `check`, `build` or any workflow; its unit tests never call `claude` or kotlin-lsp.
plugins {
    id("kronstadt.kotlin-conventions")
    application
}

dependencies {
    // Where Kronstadt keeps the Index Seed and its refresh log, to freeze the seed for a whole Benchmark.
    implementation(project(":workspace"))
    implementation(libs.serialization.json)
}

application {
    mainClass = "kronstadt.benchmark.MainKt"
}

tasks.named<JavaExec>("run") {
    // The arm with Kronstadt runs the server built from this checkout.
    dependsOn(":server:installDist")
    // Gradle's run task otherwise starts in the module directory.
    workingDir = rootProject.projectDir
}
