// Adapter: implements the CodeIntelligence port by talking to a Kotlin language server over LSP.
plugins {
    id("kronstadt.kotlin-conventions")
}

dependencies {
    api(project(":core"))
    implementation(libs.lsp4j)
    implementation(libs.coroutines.core)
}
