plugins {
    application
    idea
    alias(libs.plugins.kotlin.jvm)
}

group = "com.auri"
version = "0.0.1"

application {
    mainClass = "com.auri.cli.ConsoleAppKt"
}

dependencies {
    implementation(libs.kotlin.coroutines)
    implementation(libs.ajalt.clikt)
    implementation(libs.bundles.exposed)
    implementation(libs.lingala.zip4j)
    implementation(libs.bundles.hoplite)

    testImplementation(kotlin("test"))
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}