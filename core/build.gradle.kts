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
    api(libs.cronutils)

    implementation(libs.lingala.zip4j)
    implementation(libs.kotlin.coroutines)

    testApi(libs.bundles.kotest)
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
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}