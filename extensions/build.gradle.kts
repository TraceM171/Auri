plugins {
    `java-library`
    `java-library-distribution`
    idea
    alias(libs.plugins.kotlin.jvm)
}

group = "com.auri"
version = "0.0.1"

dependencies {
    implementation(project(":core"))

    implementation(libs.bundles.exposed)

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