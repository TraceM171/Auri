plugins {
    idea
    alias(libs.plugins.kotlin.jvm)
}

group = "com.auri"
version = "0.0.1"

dependencies {
    api(project(":core"))
    implementation(project(":extensions"))

    implementation(libs.kotlin.coroutines)
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