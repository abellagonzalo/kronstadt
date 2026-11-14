// Adapter: file system access, scoped to the project root (and later: Gradle).
plugins {
    id("kronstadt.kotlin-conventions")
}

dependencies {
    api(project(":core"))
    implementation(libs.coroutines.core)
}
