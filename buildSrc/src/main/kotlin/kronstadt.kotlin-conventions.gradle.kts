plugins {
    kotlin("jvm")
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(25)
    explicitApi()
    compilerOptions {
        allWarningsAsErrors = true
    }
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<Test>().configureEach {
    // The workspace module calls clonefile(2) through the Foreign Function API.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
