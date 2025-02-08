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
    implementation(project(":core"))
    implementation(project(":extensions"))

    implementation(libs.kotlin.coroutines)
    implementation(libs.ajalt.clikt)
    implementation(libs.touchlab.kermit)
    implementation(libs.bundles.arrow)
    implementation(libs.bundles.exposed)
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