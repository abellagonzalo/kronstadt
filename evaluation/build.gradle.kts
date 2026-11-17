// Dev-only evaluation analyser (never shipped): reads Claude Code transcripts and reports whether v1 meets its bar.
// Independent of the server; run with `./gradlew :evaluation:run --args="..."`.
plugins {
    id("kronstadt.kotlin-conventions")
    application
}

dependencies {
    implementation(libs.serialization.json)
}

application {
    mainClass = "kronstadt.evaluation.MainKt"
}

tasks.named<JavaExec>("run") {
    // Gradle's run task otherwise starts in the module directory.
    workingDir = rootProject.projectDir
}
