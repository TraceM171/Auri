plugins {
    application
    idea
    alias(libs.plugins.kotlin.jvm)
}

group = "com.auri"
version = "0.0.1"

application {
    applicationName = "auri"
    mainClass = "com.auri.cli.ConsoleAppKt"
}
dependencies {
    implementation(project(":app"))

    implementation(libs.ajalt.clikt)

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