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

// The JDK the server is built for, which its launcher runs on unless KRONSTADT_JAVA_HOME names another (#145).
val buildJavaHome: Provider<String> = javaToolchains.launcherFor(java.toolchain)
    .map { it.metadata.installationPath.asFile.absolutePath }

application {
    applicationName = "kronstadt"
    mainClass = "kronstadt.server.MainKt"
    // Index seeding calls clonefile(2) through the Foreign Function API.
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

// Records the build JDK in the launchers. The snippets in launcher/ go in front of Gradle's own "find java" step,
// which then only runs when they found no JDK.
tasks.startScripts {
    val unixSnippet = layout.projectDirectory.file("launcher/java-home.sh")
    val windowsSnippet = layout.projectDirectory.file("launcher/java-home.bat")
    inputs.files(unixSnippet, windowsSnippet)
    inputs.property("buildJavaHome", buildJavaHome)
    doLast {
        val home = buildJavaHome.get()
        val unix = unixScript.readText()
        val start = unix.indexOf("# Determine the Java command to use to start the JVM.")
        val end = unix.indexOf("\n\n# Increase the maximum file descriptors")
        check(start >= 0 && end > start) { "Gradle's start script changed: can't find where it picks java" }
        val unixHome = "'" + home.replace("'", "'\\''") + "'"
        unixScript.writeText(
            unix.substring(0, start) +
                unixSnippet.asFile.readText().replace("@BUILD_JAVA_HOME@", unixHome) +
                unix.substring(start, end) + "\nfi" + unix.substring(end),
        )
        val windows = windowsScript.readText()
        val marker = "@rem Find java.exe"
        check(marker in windows) { "Gradle's Windows start script changed: can't find where it picks java.exe" }
        val windowsCrlfSnippet = windowsSnippet.asFile.readText().replace("@BUILD_JAVA_HOME@", home).replace("\n", "\r\n")
        windowsScript.writeText(windows.replace(marker, windowsCrlfSnippet + marker))
    }
}

// Seam 2: contract tests that run the server against a real kotlin-lsp (must be on PATH, or set
// KRONSTADT_LANGUAGE_SERVER). Slow, so `test` skips them; run with `./gradlew :server:contractTest`.
tasks.test {
    useJUnitPlatform { excludeTags("contract", "launcher") }
}

// The installed launcher against fake JDKs: which JDK it runs on (#145). Part of `check`.
val launcherTest by tasks.registering(Test::class) {
    description = "Runs the installed launcher against fake JDKs."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform { includeTags("launcher") }
    val install = tasks.installDist.map { it.destinationDir }
    inputs.files(install)
    jvmArgumentProviders.add { listOf("-Dkronstadt.install=${install.get().absolutePath}", "-Dkronstadt.buildJavaHome=${buildJavaHome.get()}") }
}

tasks.check { dependsOn(launcherTest) }

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
