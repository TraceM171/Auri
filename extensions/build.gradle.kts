plugins {
    `java-library`
    `java-library-distribution`
    idea
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "com.auri"
version = "0.0.1"

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.bundles.exposed)
    implementation(libs.bundles.ktor)
    implementation(libs.skrapeit)

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