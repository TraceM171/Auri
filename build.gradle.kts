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
    implementation(libs.touchlab.kermit)
    implementation(libs.bundles.arrow)
    implementation(libs.bundles.exposed)
    implementation(libs.lingala.zip4j)
    implementation(libs.bundles.hoplite)
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.classgraph)

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