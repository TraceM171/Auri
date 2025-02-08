plugins {
    `java-library`
    idea
    alias(libs.plugins.kotlin.jvm)
}

group = "com.auri"
version = "0.0.1"


dependencies {
    api(libs.touchlab.kermit)
    api(libs.kotlinx.datetime)
    api(libs.bundles.arrow)

    implementation(libs.lingala.zip4j)
    implementation(libs.kotlin.coroutines)

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