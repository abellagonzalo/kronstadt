// Fixture for ExplicitApiContractTest: a build in explicit-API mode, which Gradle applies to main sources only.
// Never built by Kronstadt's own build; kotlin-lsp imports it.
plugins {
    kotlin("jvm") version "2.4.21"
}

repositories {
    mavenCentral()
}

kotlin {
    explicitApi()
}
