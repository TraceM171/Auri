plugins {
    application
    alias(libs.plugins.kotlin.jvm)
}

group = "com.auri"
version = "0.0.1"

application {
    mainClass = "com.auri.core.ConsoleAppKt"
}

dependencies {
    implementation(libs.ajalt.clikt)

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}