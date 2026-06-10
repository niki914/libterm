plugins {
    alias(libs.plugins.jetbrains.kotlin.jvm)
    id("maven-publish")
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
}
