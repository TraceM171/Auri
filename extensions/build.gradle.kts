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

    implementation("vbox:webservice:7.1.6")
    implementation("javax.xml.ws:jaxws-api:2.3.1")
    implementation("com.sun.xml.ws:rt:2.3.7")
    implementation("com.github.mwiede:jsch:0.2.24")


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